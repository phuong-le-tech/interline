//! Spawns the Spring Boot core, hands it the per-launch token on stdin and stops it cleanly.
//!
//! Defence in depth against orphans: the core runs in its own process group (killed as a whole after the
//! grace period) and exits by itself when its stdin closes, even if this process is killed.

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde::Serialize;
use std::io::{BufRead, BufReader, Write};
use std::os::unix::process::CommandExt;
use std::path::{Path, PathBuf};
use std::process::{Child, ChildStdin, Command, Stdio};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::{Duration, Instant};

const TOKEN_BYTES: usize = 32;
const SHUTDOWN_GRACE: Duration = Duration::from_secs(8);
const POLL_INTERVAL: Duration = Duration::from_millis(50);
/// Origin of the Vite dev server that `pnpm tauri dev` loads; the core must accept it for CORS.
const DEV_ORIGIN: &str = "http://127.0.0.1:5173";

#[derive(Clone, Serialize)]
pub struct Endpoint {
    pub port: u16,
    pub token: String,
}

pub struct Core {
    child: Child,
    stdin: Option<ChildStdin>,
    endpoint: Arc<Mutex<Option<Endpoint>>>,
}

impl Core {
    pub fn spawn(data_dir: &Path, log_dir: &Path, resources: &Path) -> Result<Core, String> {
        crate::process_registry::recover(data_dir)?;
        let mut token_bytes = [0u8; TOKEN_BYTES];
        getrandom::fill(&mut token_bytes).map_err(|e| format!("no secure randomness: {e}"))?;
        let token = URL_SAFE_NO_PAD.encode(token_bytes);

        let mut child = command(data_dir, log_dir, resources)?
            .stdin(Stdio::piped())
            .stdout(Stdio::piped())
            .stderr(Stdio::inherit())
            .process_group(0)
            .spawn()
            .map_err(|e| format!("cannot start the core: {e}"))?;
        let mut stdin = child.stdin.take().ok_or("core stdin unavailable")?;
        let stdout = child.stdout.take().ok_or("core stdout unavailable")?;
        writeln!(stdin, "{{\"token\":\"{token}\"}}").map_err(|e| format!("cannot send the token: {e}"))?;

        let endpoint = Arc::new(Mutex::new(None));
        let published = Arc::clone(&endpoint);
        thread::spawn(move || {
            for line in BufReader::new(stdout).lines().map_while(Result::ok) {
                if let Some(port) = ready_port(&line) {
                    eprintln!("{line}");
                    *published.lock().unwrap() = Some(Endpoint { port, token: token.clone() });
                }
            }
        });
        Ok(Core { child, stdin: Some(stdin), endpoint })
    }

    pub fn endpoint(&self) -> Option<Endpoint> {
        self.endpoint.lock().ok().and_then(|e| e.clone())
    }

    /// Ask for a clean exit, wait for the grace period, then kill the whole process group.
    pub fn shutdown(&mut self) {
        if let Some(mut stdin) = self.stdin.take() {
            let _ = writeln!(stdin, "{{\"cmd\":\"shutdown\"}}");
            // Dropping stdin also closes the lifeline, which the core treats the same way.
        }
        let deadline = Instant::now() + SHUTDOWN_GRACE;
        while Instant::now() < deadline {
            if matches!(self.child.try_wait(), Ok(Some(_))) {
                break;
            }
            thread::sleep(POLL_INTERVAL);
        }
        // SAFETY: killpg only signals the process group this shell created for the core.
        unsafe { libc::killpg(self.child.id() as i32, libc::SIGKILL) };
        let _ = self.child.wait();
    }
}

/// Port from the single READY line, `{"event":"ready","port":53122,"pid":4242,"version":"0.1.0"}`.
fn ready_port(line: &str) -> Option<u16> {
    let value: serde_json::Value = serde_json::from_str(line).ok()?;
    if value["event"] != "ready" {
        return None;
    }
    value["port"].as_u64().and_then(|p| u16::try_from(p).ok())
}

/// Origin the webview of a built (not `tauri dev`) app is served from.
const BUNDLE_ORIGIN: &str = "tauri://localhost";
const SIDECAR_AGENT: &str = "interline-capture";

/// The capture agent bundled next to this executable (Tauri `externalBin`), if any.
fn sidecar_agent() -> Option<PathBuf> {
    let executable = std::env::current_exe().ok()?;
    [SIDECAR_AGENT, "interline-capture-macos"].into_iter()
        .map(|name| executable.parent().unwrap().join(name)).find(|path| path.is_file())
}

/// Development launch: the built core jar, with the bundled capture agent when present and the MockAgent at
/// real-time speed otherwise. The agent stays a grandchild of the shell (shell -> JVM -> agent), which is the
/// process chain macOS attributes its permissions to. Release builds use Resources/core.
fn command(data_dir: &Path, log_dir: &Path, resources: &Path) -> Result<Command, String> {
    let repo = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
    let core = resources.join("core");
    let jar = if cfg!(debug_assertions) { repo.join("services/core/app/target/interline-core.jar") }
        else { core.join("interline-core.jar") };
    if !jar.is_file() {
        return Err(format!("core not built: run `./mvnw -DskipTests package` in services/core ({})", jar.display()));
    }
    let executable = if cfg!(debug_assertions) {
        std::env::var_os("JAVA_HOME").map_or("java".into(), |home| Path::new(&home).join("bin/java"))
    } else { core.join("runtime/bin/java") };
    let mut java = Command::new(executable);
    sanitize_environment(&mut java);
    if !cfg!(debug_assertions) {
        java.current_dir(&core).args(["-XX:SharedArchiveFile=interline-core.jsa", "-Xss512k",
            "-XX:+UseSerialGC", "-Xmx256m", "-Djava.awt.headless=true", "-Dapple.awt.UIElement=true"])
            .arg(format!("-Dorg.sqlite.lib.path={}", core.join("native").display()));
    }
    java.arg("-jar")
        .arg(if cfg!(debug_assertions) { jar } else { "interline-core.jar".into() })
        .arg(format!("--data-dir={}", data_dir.display()))
        .arg(format!("--log-dir={}", log_dir.display()));
    match sidecar_agent() {
        Some(agent) => java.arg(format!("--agent={}", agent.display())),
        None if cfg!(debug_assertions) => java
            .arg(format!("--agent={}", repo.join("services/core/mock-agent/bin/mock-agent").display()))
            .arg("--agent-arg=--speed=1"),
        None => return Err("bundled capture agent is missing".into()),
    };
    // Development switch for agent flags such as `--voice-processing` (used by scripts/m1/human-gate.sh).
    #[cfg(debug_assertions)]
    for flag in std::env::var("INTERLINE_AGENT_ARGS").unwrap_or_default().split_whitespace() {
        java.arg(format!("--agent-arg={flag}"));
    }
    java.arg("--parent=stdio")
        .arg(format!("--allow-origin={DEV_ORIGIN}"))
        .arg(format!("--allow-origin={BUNDLE_ORIGIN}"));
    Ok(java)
}

fn sanitize_environment(command: &mut Command) {
    let keys: Vec<_> = std::env::vars_os().map(|(key, _)| key)
        .chain(command.get_envs().map(|(key, _)| key.to_owned())).collect();
    for key in keys {
        let name = key.to_string_lossy();
        let development_override = !cfg!(debug_assertions)
            && ["INTERLINE_DATA_DIR", "INTERLINE_LOG_DIR", "INTERLINE_AGENT_ARGS"].contains(&name.as_ref());
        if development_override || name.starts_with("DYLD_")
            || ["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS"].contains(&name.as_ref()) {
            command.env_remove(key);
        }
    }
}

#[cfg(test)]
mod environment_tests {
    use super::*;
    #[test]
    fn core_environment_removes_injection_and_preserves_other_values() {
        let mut command = Command::new("/usr/bin/env");
        command.env_clear().env("SENTINEL", "kept");
        let mut blocked = vec!["JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS", "DYLD_LIBRARY_PATH", "DYLD_INSERT_LIBRARIES"];
        if !cfg!(debug_assertions) { blocked.extend(["INTERLINE_DATA_DIR", "INTERLINE_LOG_DIR", "INTERLINE_AGENT_ARGS"]); }
        for key in &blocked { command.env(key, "blocked"); }
        sanitize_environment(&mut command);
        for key in &blocked {
            assert!(!command.get_envs().any(|(name, value)| name == *key && value.is_some()));
        }
        let output = command.output().unwrap();
        assert!(output.status.success());
        assert_eq!(String::from_utf8(output.stdout).unwrap(), "SENTINEL=kept\n");
    }
}

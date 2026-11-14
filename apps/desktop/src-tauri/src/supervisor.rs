//! Spawns the Spring Boot core, hands it the per-launch token on stdin and stops it cleanly.
//!
//! Defence in depth against orphans: the core runs in its own process group (killed as a whole after the
//! grace period) and exits by itself when its stdin closes, even if this process is killed.

use base64::{engine::general_purpose::URL_SAFE_NO_PAD, Engine};
use serde::Serialize;
use std::io::{BufRead, BufReader, Write};
use std::os::unix::process::CommandExt;
use std::path::Path;
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
    pub fn spawn(data_dir: &Path, log_dir: &Path) -> Result<Core, String> {
        let mut token_bytes = [0u8; TOKEN_BYTES];
        getrandom::fill(&mut token_bytes).map_err(|e| format!("no secure randomness: {e}"))?;
        let token = URL_SAFE_NO_PAD.encode(token_bytes);

        let mut child = command(data_dir, log_dir)?
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

/// Development launch: the built core jar with the MockAgent at real-time speed. Bundled runtimes arrive in M5.
fn command(data_dir: &Path, log_dir: &Path) -> Result<Command, String> {
    if !cfg!(debug_assertions) {
        return Err("only the development launch exists in M0".to_string());
    }
    let repo = Path::new(env!("CARGO_MANIFEST_DIR")).join("../../..");
    let jar = repo.join("services/core/app/target/interline-core.jar");
    if !jar.is_file() {
        return Err(format!("core not built: run `./mvnw -DskipTests package` in services/core ({})", jar.display()));
    }
    let mut java = Command::new(std::env::var_os("JAVA_HOME").map_or("java".into(), |home| Path::new(&home).join("bin/java")));
    java.arg("-jar")
        .arg(jar)
        .arg(format!("--data-dir={}", data_dir.display()))
        .arg(format!("--log-dir={}", log_dir.display()))
        .arg(format!("--agent={}", repo.join("services/core/mock-agent/bin/mock-agent").display()))
        .arg("--agent-arg=--speed=1")
        .arg("--parent=stdio")
        .arg(format!("--allow-origin={DEV_ORIGIN}"));
    Ok(java)
}

#[cfg(any(target_os = "macos", test))]
fn parse_status(output: &str) -> &'static str {
    match output.trim() {
        "FileVault is On." => "on",
        "FileVault is Off." => "off",
        _ => "unknown",
    }
}

#[cfg(any(target_os = "macos", test))]
fn command_status(command: &mut std::process::Command, timeout: std::time::Duration) -> &'static str {
    use std::{process::Stdio, time::{Duration, Instant}};
    let Ok(mut child) = command.stdin(Stdio::null()).stdout(Stdio::piped()).stderr(Stdio::null()).spawn() else { return "unknown"; };
    let deadline = Instant::now() + timeout;
    loop {
        match child.try_wait() {
            Ok(Some(_)) => return child.wait_with_output().ok().filter(|out| out.status.success())
                .map(|out| parse_status(&String::from_utf8_lossy(&out.stdout))).unwrap_or("unknown"),
            Ok(None) if Instant::now() < deadline => std::thread::sleep(Duration::from_millis(20)),
            _ => { let _ = child.kill(); let _ = child.wait(); return "unknown"; }
        }
    }
}

#[tauri::command]
pub async fn filevault_status() -> &'static str {
    tauri::async_runtime::spawn_blocking(|| {
        #[cfg(target_os = "macos")]
        { command_status(std::process::Command::new("/usr/bin/fdesetup").arg("status"), std::time::Duration::from_secs(2)) }
        #[cfg(not(target_os = "macos"))]
        { "unknown" }
    }).await.unwrap_or("unknown")
}

#[cfg(test)]
mod tests {
    use super::parse_status;

    #[test]
    fn maps_only_unambiguous_filevault_output() {
        assert_eq!(parse_status("FileVault is On.\n"), "on");
        assert_eq!(parse_status("FileVault is Off.\n"), "off");
        for output in ["", "Unknown", "Error: requires root", "garbage", "FileVault is On.\nFileVault is Off."] {
            assert_eq!(parse_status(output), "unknown");
        }
    }
    #[test]
    fn command_timeout_and_failure_are_unknown() {
        use std::{process::Command, time::{Duration, Instant}};
        let started = Instant::now();
        assert_eq!(super::command_status(Command::new("/bin/sleep").arg("5"), Duration::from_millis(20)), "unknown");
        assert!(started.elapsed() < Duration::from_secs(2));
        assert_eq!(super::command_status(&mut Command::new("/usr/bin/false"), Duration::from_secs(1)), "unknown");
    }

}

//! Match the core's portable pid/start-time/executable identities before orphan recovery.
use serde::Deserialize;
use std::path::{Path, PathBuf};

#[derive(Debug, Deserialize, PartialEq)]
#[serde(rename_all = "camelCase")]
struct Identity {
    pid: i32,
    start_time: u64,
    executable: PathBuf,
}

#[derive(Deserialize)]
struct Snapshot {
    owner: Option<Identity>,
    core: Identity,
    agent: Option<Identity>,
}

#[cfg(target_os = "macos")]
fn identity(pid: i32) -> Option<Identity> {
    let mut info = std::mem::MaybeUninit::<libc::proc_bsdinfo>::uninit();
    let size = std::mem::size_of::<libc::proc_bsdinfo>() as i32;
    let mut path = [0u8; 4096];
    // SAFETY: libproc receives writable buffers of the declared sizes; info is read only on success.
    unsafe {
        if libc::proc_pidinfo(pid, libc::PROC_PIDTBSDINFO, 0, info.as_mut_ptr().cast(), size) != size
            || libc::proc_pidpath(pid, path.as_mut_ptr().cast(), path.len() as u32) <= 0 {
            return None;
        }
        let info = info.assume_init();
        let end = path.iter().position(|b| *b == 0)?;
        Some(Identity {
            pid,
            start_time: info.pbi_start_tvsec * 1000 + info.pbi_start_tvusec / 1000,
            executable: Path::new(std::str::from_utf8(&path[..end]).ok()?).canonicalize().ok()?,
        })
    }
}

#[cfg(not(target_os = "macos"))]
fn identity(_pid: i32) -> Option<Identity> { None }

fn matches(saved: &Identity) -> bool {
    saved.pid > 1 && saved.start_time > 0 && identity(saved.pid).as_ref() == Some(saved)
}

pub fn recover(data: &Path) -> Result<(), String> {
    let file = data.join("run/pids.json");
    let bytes = match std::fs::read(file) {
        Ok(bytes) => bytes,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(format!("cannot read process registry: {e}")),
    };
    let saved: Snapshot = serde_json::from_slice(&bytes).map_err(|e| format!("invalid process registry: {e}"))?;
    if saved.owner.as_ref().is_some_and(matches) && matches(&saved.core) {
        return Err("Interline is already running for this data directory".into());
    }
    for process in [Some(saved.core), saved.agent].into_iter().flatten() {
        if matches(&process) {
            // SAFETY: only the identity just matched in the kernel is signalled, never a process group.
            if unsafe { libc::kill(process.pid, libc::SIGKILL) } != 0 {
                return Err(format!("cannot stop leftover process {}", process.pid));
            }
        }
    }
    Ok(())
}

#[cfg(all(test, target_os = "macos"))]
mod tests {
    use super::*;
    #[test]
    fn recycled_pid_and_changed_executable_do_not_match() {
        let mut saved = identity(std::process::id() as i32).unwrap();
        assert!(matches(&saved));
        saved.start_time += 1;
        assert!(!matches(&saved));
        saved.start_time -= 1;
        saved.executable = "/different/executable".into();
        assert!(!matches(&saved));
    }
}

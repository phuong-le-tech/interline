//! Match the core's portable pid/start-time/executable identities before orphan recovery.
use serde::Deserialize;
use std::io::Read;
use std::os::unix::fs::{DirBuilderExt, PermissionsExt};
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

fn signalable(saved: &Identity) -> bool {
    saved.pid != std::process::id() as i32 && matches(saved)
}

pub fn recover(data: &Path) -> Result<(), String> {
    let run = data.join("run");
    std::fs::DirBuilder::new().recursive(true).mode(0o700).create(&run).map_err(|e| e.to_string())?;
    std::fs::set_permissions(&run, std::fs::Permissions::from_mode(0o700)).map_err(|e| e.to_string())?;
    let path = run.join("pids.json");
    let mut file = match std::fs::File::open(&path) {
        Ok(file) => file,
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(e) => return Err(format!("cannot read process registry: {e}")),
    };
    file.set_permissions(std::fs::Permissions::from_mode(0o600)).map_err(|e| e.to_string())?;
    let mut bytes = Vec::new();
    if file.metadata().map_err(|e| e.to_string())?.len() <= 65536 {
        (&mut file).take(65536).read_to_end(&mut bytes).map_err(|e| e.to_string())?;
    }
    let saved = serde_json::from_slice::<Snapshot>(&bytes).ok().filter(|saved| {
        [Some(&saved.core), saved.owner.as_ref(), saved.agent.as_ref()].into_iter().flatten()
            .all(|id| id.pid > 0 && id.start_time > 0 && id.executable.is_absolute())
    });
    let Some(saved) = saved.filter(|_| file.metadata().is_ok_and(|m| m.len() <= 65536)) else {
        std::fs::remove_file(path).map_err(|e| e.to_string())?;
        return Ok(());
    };
    if saved.owner.as_ref().is_some_and(matches) && matches(&saved.core) {
        return Err("Interline is already running for this data directory".into());
    }
    for process in [Some(saved.core), saved.agent].into_iter().flatten() {
        if signalable(&process) {
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
    use std::os::unix::fs::PermissionsExt;

    fn with_registry(contents: &[u8], check: impl FnOnce(&Path)) {
        let data = std::env::temp_dir().join(format!("interline-registry-{}-{}", std::process::id(),
            std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_nanos()));
        std::fs::create_dir_all(data.join("run")).unwrap();
        std::fs::write(data.join("run/pids.json"), contents).unwrap();
        check(&data);
        std::fs::remove_dir_all(data).unwrap();
    }

    fn encoded(process: &Identity) -> Vec<u8> {
        serde_json::to_vec(&serde_json::json!({"core": {"pid": process.pid,
            "startTime": process.start_time, "executable": process.executable}})).unwrap()
    }

    #[test]
    fn recovery_cannot_signal_itself() {
        let current = identity(std::process::id() as i32).unwrap();
        assert!(!signalable(&current));
        with_registry(&encoded(&current), |data| assert!(recover(data).is_ok()));
    }

    #[test]
    fn oversized_and_malformed_registries_do_not_kill() {
        let mut child = std::process::Command::new("/bin/sleep").arg("30").spawn().unwrap();
        let mut oversized = encoded(&identity(child.id() as i32).unwrap());
        oversized.resize(65537, b' ');
        for contents in [oversized, b"{".to_vec(), b"null".to_vec()] {
            with_registry(&contents, |data| assert!(recover(data).is_ok()));
        }
        let running = child.try_wait().unwrap().is_none();
        let _ = child.kill();
        child.wait().unwrap();
        assert!(running);
    }

    #[test]
    fn registry_permissions_are_private() {
        with_registry(b"{", |data| {
            recover(data).unwrap();
            assert_eq!(std::fs::metadata(data.join("run")).unwrap().permissions().mode() & 0o777, 0o700);
        });
        let current = identity(std::process::id() as i32).unwrap();
        with_registry(&encoded(&current), |data| {
            recover(data).unwrap();
            assert_eq!(std::fs::metadata(data.join("run/pids.json")).unwrap().permissions().mode() & 0o777, 0o600);
        });
    }
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

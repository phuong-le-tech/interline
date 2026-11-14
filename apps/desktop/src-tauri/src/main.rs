//! Interline desktop shell: shows the window at once, starts the core in the background and hands the
//! UI the endpoint it needs. All product logic lives in the core; this file stays small on purpose.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod supervisor;

use std::sync::Mutex;
use supervisor::{Core, Endpoint};
use tauri::{Manager, RunEvent, State};

/// The running core, or the reason it could not start.
struct CoreState(Mutex<Result<Core, String>>);

#[tauri::command]
fn get_core_endpoint(state: State<CoreState>) -> Result<Endpoint, String> {
    match &*state.0.lock().map_err(|_| "core state poisoned".to_string())? {
        Ok(core) => core.endpoint().ok_or_else(|| "core is starting".to_string()),
        Err(reason) => Err(reason.clone()),
    }
}

fn main() {
    let app = tauri::Builder::default()
        .manage(CoreState(Mutex::new(Err("core is starting".to_string()))))
        .invoke_handler(tauri::generate_handler![get_core_endpoint])
        .setup(|app| {
            let data_dir = app.path().app_data_dir()?;
            let log_dir = app.path().app_log_dir()?;
            // Spawning only forks the child; READY is awaited on a reader thread, so the window is not blocked.
            let core = Core::spawn(&data_dir, &log_dir);
            *app.state::<CoreState>().0.lock().unwrap() = core;
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("failed to build the Interline shell");

    app.run(|handle, event| {
        if let RunEvent::Exit = event {
            if let Ok(Ok(core)) = handle.state::<CoreState>().0.lock().as_deref_mut() {
                core.shutdown();
            }
        }
    });
}

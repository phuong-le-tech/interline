//! Interline desktop shell: shows the window at once, starts the core in the background and hands the
//! UI the endpoint it needs. All product logic lives in the core; this file stays small on purpose.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod supervisor;
mod process_registry;

use std::sync::Mutex;
use std::process::Command;
use supervisor::{Core, Endpoint};
use tauri::{AppHandle, Manager, RunEvent, State};
#[cfg(target_os = "macos")]
use tauri::{image::Image, menu::{Menu, MenuItem}, tray::TrayIconBuilder};
use tauri_plugin_notification::{NotificationExt, PermissionState};

/// The only URL scheme the UI may ask the shell to open: the macOS privacy panes.
const SETTINGS_SCHEME: &str = "x-apple.systempreferences:";

/// The running core, or the reason it could not start.
struct CoreState(Mutex<Result<Core, String>>);

#[tauri::command]
fn open_system_settings(uri: String) -> Result<(), String> {
    if !uri.starts_with(SETTINGS_SCHEME) {
        return Err("only System Settings links can be opened".to_string());
    }
    // `--` stops a crafted value from being read as an option.
    Command::new("/usr/bin/open").arg("--").arg(&uri).status().map(|_| ()).map_err(|e| e.to_string())
}

/// Shows a system notification. It only informs: nothing here starts a recording.
#[tauri::command]
fn notify(app: AppHandle, title: String, body: String) -> Result<(), String> {
    let notification = app.notification();
    if notification.permission_state().map_err(|e| e.to_string())? != PermissionState::Granted
        && notification.request_permission().map_err(|e| e.to_string())? != PermissionState::Granted
    {
        return Ok(()); // the user declined: the in-app banner still shows
    }
    notification.builder().title(title).body(body).show().map_err(|e| e.to_string())
}

#[tauri::command]
fn get_core_endpoint(state: State<CoreState>) -> Result<Endpoint, String> {
    match &*state.0.lock().map_err(|_| "core state poisoned".to_string())? {
        Ok(core) => core.endpoint().ok_or_else(|| "core is starting".to_string()),
        Err(reason) => Err(reason.clone()),
    }
}

fn validate_tray_state(state: &str) -> Result<(), String> {
    match state {
        "idle" | "recording" | "warning" => Ok(()),
        _ => Err("invalid tray state".to_string()),
    }
}

#[cfg(target_os = "macos")]
fn tray_icon(state: &str) -> Image<'static> {
    match state {
        "recording" => tauri::include_image!("icons/tray-recording.png"),
        "warning" => tauri::include_image!("icons/tray-warning.png"),
        _ => tauri::include_image!("icons/tray-idle.png"),
    }
}

#[tauri::command]
fn set_tray_state(app: AppHandle, state: String) -> Result<(), String> {
    validate_tray_state(&state)?;
    #[cfg(target_os = "macos")]
    {
        let tray = app.tray_by_id("state").ok_or("tray unavailable")?;
        tray.set_icon_with_as_template(Some(tray_icon(&state)), state == "idle").map_err(|e| e.to_string())?;
        tray.set_tooltip(Some(format!("Interline - {}", state.to_uppercase()))).map_err(|e| e.to_string())?;
    }
    #[cfg(not(target_os = "macos"))]
    let _ = app;
    Ok(())
}

#[cfg(target_os = "macos")]
fn setup_tray(app: &tauri::App) -> tauri::Result<()> {
    let open = MenuItem::with_id(app, "open", "Open Interline", true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", "Quit", true, None::<&str>)?;
    TrayIconBuilder::with_id("state")
        .icon(tray_icon("idle"))
        .icon_as_template(true)
        .tooltip("Interline - IDLE")
        .menu(&Menu::with_items(app, &[&open, &quit])?)
        .on_menu_event(|app, event| match event.id.as_ref() {
            "open" => {
                if let Some(window) = app.get_webview_window("main") {
                    if let Err(error) = window.show().and_then(|_| window.set_focus()) {
                        eprintln!("cannot open Interline: {error}");
                    }
                }
            }
            "quit" => app.exit(0),
            _ => {}
        })
        .build(app)?;
    Ok(())
}

fn main() {
    let app = tauri::Builder::default()
        .plugin(tauri_plugin_notification::init())
        .manage(CoreState(Mutex::new(Err("core is starting".to_string()))))
        .invoke_handler(tauri::generate_handler![get_core_endpoint, open_system_settings, notify, set_tray_state])
        .on_window_event(|window, event| {
            #[cfg(target_os = "macos")]
            if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                if let Err(error) = window.hide() {
                    eprintln!("cannot hide Interline: {error}");
                }
            }
        })
        .setup(|app| {
            #[cfg(target_os = "macos")]
            setup_tray(app)?;
            let data_dir = app.path().app_data_dir()?;
            let log_dir = app.path().app_log_dir()?;
            #[cfg(debug_assertions)]
            let data_dir = std::env::var_os("INTERLINE_DATA_DIR").map(std::path::PathBuf::from).unwrap_or(data_dir);
            #[cfg(debug_assertions)]
            let log_dir = std::env::var_os("INTERLINE_LOG_DIR").map(std::path::PathBuf::from).unwrap_or(log_dir);
            // Spawning only forks the child; READY is awaited on a reader thread, so the window is not blocked.
            let core = Core::spawn(&data_dir, &log_dir, &app.path().resource_dir()?);
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

#[cfg(test)]
mod tray_state_tests {
    use super::validate_tray_state;

    #[test]
    fn accepts_supported_tray_states() {
        for state in ["idle", "recording", "warning"] {
            assert_eq!(validate_tray_state(state), Ok(()), "state: {state:?}");
        }
    }

    #[test]
    fn rejects_unknown_tray_states() {
        for state in [
            "", "unknown", "degraded", "capture restarting", "IDLE", " idle", "idle ", "recording\n",
        ] {
            assert!(validate_tray_state(state).is_err(), "state: {state:?}");
        }
    }
}

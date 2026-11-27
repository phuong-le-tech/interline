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
}

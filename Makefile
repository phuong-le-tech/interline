# Contributor targets (ADR-004 install path D). macOS only.
DEV_KEYCHAIN ?= $(HOME)/Library/Keychains/interline-dev.keychain-db
# Not a secret: the keychain only holds a local self-signed key that signs your own builds.
DEV_KEYCHAIN_PASSWORD ?= interline-dev
DEV_IDENTITY ?= Interline Dev Signing
APP := apps/desktop/src-tauri/target/release/bundle/macos/Interline.app

.PHONY: signing-identity install
# Creates a self-signed code signing identity in its own keychain file, never in your login keychain.
# A stable identity keeps your microphone and system-audio permissions across rebuilds.
signing-identity:
	@if [ -e "$(DEV_KEYCHAIN)" ]; then echo "$(DEV_KEYCHAIN) exists; delete it with 'security delete-keychain' to recreate."; \
	else scripts/package/make-identity.sh "$(DEV_KEYCHAIN)" "$(DEV_IDENTITY)" "$(DEV_KEYCHAIN_PASSWORD)"; fi

# Builds, signs with the local identity and copies the app to ~/Applications.
install:
	@test -e "$(DEV_KEYCHAIN)" || { echo "Run 'make signing-identity' once first."; exit 1; }
	security unlock-keychain -p "$(DEV_KEYCHAIN_PASSWORD)" "$(DEV_KEYCHAIN)"
	scripts/package/build.sh
	SIGN_IDENTITY="$(DEV_IDENTITY)" SIGN_KEYCHAIN="$(DEV_KEYCHAIN)" scripts/package/sign-macos.sh "$(APP)"
	mkdir -p "$(HOME)/Applications"
	rm -rf "$(HOME)/Applications/Interline.app"
	ditto "$(APP)" "$(HOME)/Applications/Interline.app"
	@echo "Installed ~/Applications/Interline.app"

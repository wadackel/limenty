{
  description = "Limenty — Android SSH Terminal";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs =
    { self, nixpkgs }:
    let
      systems = [
        "aarch64-darwin"
        "x86_64-darwin"
        "aarch64-linux"
        "x86_64-linux"
      ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forAllSystems (pkgs: {
        default = pkgs.mkShell {
          # Gradle itself is deliberately absent: the project's version is pinned by
          # gradle/wrapper/gradle-wrapper.properties, and a second copy on PATH invites builds
          # that silently run a different one.
          packages = [
            # Headless: nothing here opens a window, and on Linux the full JDK brings GTK and X11
            # with it, which CI downloads on every run. On macOS both names are the same Zulu build.
            pkgs.jdk17_headless
            pkgs.ktlint
          ];

          shellHook = ''
            export JAVA_HOME="${pkgs.jdk17_headless}"
            export ANDROID_HOME="''${ANDROID_HOME:-$HOME/Library/Android/sdk}"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export GRADLE_USER_HOME="''${GRADLE_USER_HOME:-$HOME/.gradle}"
            export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
          '';
        };

        # Only for tools/sshd. Kept out of the default shell so the build and CI's check job do
        # not fetch a Python they never run.
        sshd = pkgs.mkShell {
          packages = [ (pkgs.python3.withPackages (p: [ p.asyncssh ])) ];
        };
      });
    };
}

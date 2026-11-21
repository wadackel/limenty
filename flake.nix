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
            pkgs.jdk17
            pkgs.ktlint
          ];

          shellHook = ''
            export JAVA_HOME="${pkgs.jdk17}"
            export ANDROID_HOME="''${ANDROID_HOME:-$HOME/Library/Android/sdk}"
            export ANDROID_SDK_ROOT="$ANDROID_HOME"
            export GRADLE_USER_HOME="''${GRADLE_USER_HOME:-$HOME/.gradle}"
            export PATH="$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
          '';
        };
      });
    };
}

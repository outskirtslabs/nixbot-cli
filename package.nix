{
  lib,
  stdenv,
  makeWrapper,
  babashka,
  git,
}:

stdenv.mkDerivation rec {
  pname = "nixbot-cli";
  version = "0.1.0";
  src = ./.;

  nativeBuildInputs = [ makeWrapper ];
  dontBuild = true;

  installPhase = ''
    runHook preInstall

    mkdir -p $out/bin
    cp nixbot-cli $out/bin/nixbot-cli
    chmod +x $out/bin/nixbot-cli
    wrapProgram $out/bin/nixbot-cli \
      --prefix PATH : ${
        lib.makeBinPath [
          babashka
          git
        ]
      }

    runHook postInstall
  '';

  meta = with lib; {
    description = "Inspect and control Nixbot CI builds from the terminal";
    homepage = "https://github.com/outskirtslabs/nixbot-cli";
    license = licenses.eupl12;
    maintainers = with maintainers; [ ramblurr ];
    platforms = babashka.meta.platforms;
    mainProgram = "nixbot-cli";
  };
}

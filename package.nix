{
  lib,
  stdenv,
  makeWrapper,
  babashka,
}:

stdenv.mkDerivation rec {
  pname = "tmux-buddy";
  version = "0.3.1";
  src = ./.;
  nativeBuildInputs = [ makeWrapper ];
  dontBuild = true;
  installPhase = ''
    runHook preInstall
    mkdir -p $out/bin
    cp tmuxb $out/bin/tmuxb
    chmod +x $out/bin/tmuxb
    wrapProgram $out/bin/tmuxb \
      --prefix PATH : ${lib.makeBinPath [ babashka ]}
    runHook postInstall
  '';

  meta = with lib; {
    description = "TODO";
    homepage = "https://github.com/ramblurr/tmux-buddy";
    changelog = "https://github.com/ramblurr/tmux-buddy/releases/tag/v${version}";
    license = licenses.eupl12;
    maintainers = with maintainers; [ lib.maintainers.ramblurr ];
    platforms = babashka.meta.platforms;
    mainProgram = "tmuxb";
  };
}

{
  lib,
  stdenv,
  makeWrapper,
  babashka,
  tmux,
  procps,
}:

stdenv.mkDerivation rec {
  pname = "tmux-buddy";
  version = "0.4.0";
  src = ./.;
  nativeBuildInputs = [ makeWrapper ];
  dontBuild = true;
  doCheck = true;
  nativeCheckInputs = [ babashka tmux procps ];
  checkPhase = ''
    runHook preCheck
    export HOME=$(mktemp -d)
    export SHELL=${stdenv.shell}
    unset TMUX
    bb --classpath src:test -e "
      (require '[clojure.test :as test]
               'tmuxb-test 'tmuxb-integration-test 'tmuxb.lib.send-test)
      (let [{:keys [fail error]} (test/run-tests
                                 'tmuxb-test 'tmuxb-integration-test 'tmuxb.lib.send-test)]
        (System/exit (if (zero? (+ fail error)) 0 1)))"
    runHook postCheck
  '';
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

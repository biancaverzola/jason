#!/bin/sh
# Roda um exemplo de tipagem com a saída no terminal.
#
# uso:   ./rodar.sh <exemplo> [opção=valor ...]
# ex.:   ./rodar.sh cozinha
#        ./rodar.sh mensagens jason.typecheck.runtime.onfail=fail
#        ./rodar.sh mensagens jason.typecheck=false
#
# As opções são as mesmas do logging.properties (ver decisoes-verificador.txt).
#
# Este script sempre mostra a saída no TERMINAL e IGNORA o logging.properties da pasta
# do exemplo. Para rodar do jeito normal do Jason (com a janela e as opções do
# logging.properties da pasta), entre na pasta do exemplo e rode:
#     java -cp ~/ufsc/tcc/jason/jason-cli/build/bin/jason-cli-all-3.3.4.jar jason.infra.local.RunLocalMAS <arquivo>.mas2j

AQUI=$(cd "$(dirname "$0")" && pwd)
JASON=$(cd "$AQUI/../.." && pwd)
JAR=$(ls "$JASON"/jason-cli/build/bin/jason-cli-all-*.jar 2>/dev/null | tail -1)

if [ -z "$1" ] || [ ! -d "$AQUI/$1" ]; then
    echo "uso: ./rodar.sh <exemplo> [opção=valor ...]"
    echo "exemplos: $(cd "$AQUI" && ls -d */ | tr -d / | tr '\n' ' ')"
    exit 1
fi
if [ -z "$JAR" ]; then
    echo "Jason não compilado: rode ./gradlew na pasta $JASON"
    exit 1
fi

EXEMPLO=$1; shift

# configuração de log: saída no terminal + as opções passadas
LOG=$(mktemp)
cp "$JASON/jason-interpreter/src/main/resources/templates/console-info-logging.properties" "$LOG"
for opcao in "$@"; do
    echo "$opcao" | sed 's/=/ = /' >> "$LOG"
done

cd "$AQUI/$EXEMPLO"
echo "(se o sistema iniciar, ele continua rodando: aperte Ctrl+C para encerrar)"
echo
java -cp "$JAR" jason.infra.local.RunLocalMAS "$(ls *.mas2j)" --log-conf "$LOG" --no-net
rm -f "$LOG"

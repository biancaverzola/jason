#!/bin/sh
# Roda um exemplo de tipagem com a infraestrutura JADE (agentes distribuídos),
# com a saída no terminal.
#
# uso:   ./rodar-jade.sh <exemplo> [container] [opção=valor ...]
#
#   <exemplo>     cozinha, mensagens ou criar (os .mas2j ficam na pasta jade/)
#   [container]   sem container: roda o container PRINCIPAL do JADE
#                 com container (ex.: c1): roda esse container e os agentes dele
#   opção=valor   as mesmas opções do logging.properties
#
# ex.:   ./rodar-jade.sh cozinha
#        ./rodar-jade.sh cozinha jason.typecheck.onfail=stop
#        ./rodar-jade.sh criar
#        ./rodar-jade.sh mensagens            (terminal 1: container principal)
#        ./rodar-jade.sh mensagens c2         (terminal 2: agente b)
#        ./rodar-jade.sh mensagens c1         (terminal 3: agente a)
#
# Por que uma pasta temporária: no JADE, cada agente, ao ser criado, relê o
# logging.properties da pasta atual e ignora o --log-conf (é assim no Jason original,
# ver JadeAgArch.setup). Então o script monta uma pasta temporária com:
#   - um logging.properties de terminal, com as opções passadas
#   - atalhos (links) para os .asl do exemplo
#   - o .mas2j da pasta jade/
# e roda o sistema de dentro dela.

AQUI=$(cd "$(dirname "$0")" && pwd)
JASON=$(cd "$AQUI/../.." && pwd)
JAR=$(ls "$JASON"/jason-cli/build/bin/jason-cli-all-*.jar 2>/dev/null | tail -1)

if [ -z "$1" ] || [ ! -f "$AQUI/jade/$1.mas2j" ]; then
    echo "uso: ./rodar-jade.sh <exemplo> [container] [opção=valor ...]"
    echo "exemplos: $(cd "$AQUI/jade" && ls *.mas2j | sed 's/\.mas2j//' | tr '\n' ' ')"
    exit 1
fi
if [ -z "$JAR" ]; then
    echo "Jason não compilado: rode ./gradlew na pasta $JASON"
    exit 1
fi

EXEMPLO=$1; shift
CONTAINER=""
if [ -n "$1" ] && ! echo "$1" | grep -q "="; then
    CONTAINER=$1; shift
fi

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT INT TERM

ln -s "$AQUI/$EXEMPLO"/*.asl "$TMP/"
cp "$AQUI/jade/$EXEMPLO.mas2j" "$TMP/"
cp "$JASON/jason-interpreter/src/main/resources/templates/console-info-logging.properties" "$TMP/logging.properties"
echo "jade.level = OFF" >> "$TMP/logging.properties"
for opcao in "$@"; do
    echo "$opcao" | sed 's/=/ = /' >> "$TMP/logging.properties"
done

cd "$TMP"
if [ -z "$CONTAINER" ]; then
    echo "(JADE: container principal. O sistema continua rodando: aperte Ctrl+C para encerrar)"
    echo
    # o nome faz o container principal criar só os agentes sem "at" no .mas2j
    # (os agentes com at "c1", at "c2"... ficam para os outros containers)
    java -cp "$JAR" jason.infra.jade.RunJadeMAS "$EXEMPLO.mas2j" -container-name Main-Container
else
    echo "(JADE: container $CONTAINER. O container principal precisa estar rodando. Ctrl+C para encerrar)"
    echo
    java -cp "$JAR" jason.infra.jade.RunJadeMAS "$EXEMPLO.mas2j" -container -container-name "$CONTAINER"
fi

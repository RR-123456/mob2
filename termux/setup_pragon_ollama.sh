#!/data/data/com.termux/files/usr/bin/bash
# One-shot setup of a fast, Pragon-flavoured Ollama model inside Termux (phone only, no PC).
#   bash setup_pragon_ollama.sh                 -> qwen3.5:2b-q4_K_M (good balance, can see pictures)
#   bash setup_pragon_ollama.sh qwen3.5:0.8b    -> fastest, weakest
#   bash setup_pragon_ollama.sh qwen3.5:4b-q4_K_M -> smartest, needs ~8 GB RAM and patience
set -e
BASE="${1:-qwen3.5:2b-q4_K_M}"

pkg update -y
pkg install -y ollama
termux-wake-lock || true

if ! curl -s http://127.0.0.1:11434 >/dev/null 2>&1; then
  nohup ollama serve > "$HOME/ollama.log" 2>&1 &
  sleep 6
fi

echo "Downloading $BASE ..."
ollama pull "$BASE"

cat > "$HOME/Modelfile.pragon" <<MODELFILE
FROM $BASE
PARAMETER temperature 0.4
PARAMETER top_p 0.9
PARAMETER num_ctx 3072
PARAMETER num_predict 300
SYSTEM """You are PRAGON, a calm, quick personal AI on the user's Android phone. Your replies are read aloud: plain sentences only, no markdown, lists or emojis, usually one to three short sentences, starting with the answer. Reply in the language the user is using. Never invent facts; if you do not know, say so."""
MODELFILE

ollama create pragon -f "$HOME/Modelfile.pragon"
echo
echo "Done. In PragonMob: Settings > AI engine > AUTO (or OLLAMA)."
echo "  Address: http://127.0.0.1:11434    Model: pragon"
echo "  Tick 'My model has its own personality' if you want this Modelfile's character to win."

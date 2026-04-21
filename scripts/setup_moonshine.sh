#!/bin/bash
# Moonshine setup script for Shadowing Trainer Android
# Run from project root: bash scripts/setup_moonshine.sh

set -e

CPP_DIR="app/src/main/cpp"
ASSETS_DIR="app/src/main/assets/moonshine"

echo "=== Moonshine Setup for Shadowing Trainer ==="

# Step 1: Clone moonshine core
echo ""
echo "[1/3] Cloning Moonshine core library..."
if [ -d "$CPP_DIR/moonshine-core" ]; then
    echo "  moonshine-core already exists, skipping clone."
else
    cd "$CPP_DIR"
    git clone --depth 1 https://github.com/usefulsensors/moonshine.git moonshine-repo-tmp
    cp -r moonshine-repo-tmp/core moonshine-core
    rm -rf moonshine-repo-tmp
    cd -
    echo "  ✓ Moonshine core cloned to $CPP_DIR/moonshine-core"
fi

# Step 2: Create model directory
echo ""
echo "[2/3] Setting up model directory..."
mkdir -p "$ASSETS_DIR/tiny-en"
echo "  ✓ Model directory: $ASSETS_DIR/tiny-en"

# Step 3: Check if models exist
echo ""
echo "[3/3] Checking model files..."
MODEL_DIR="$ASSETS_DIR/tiny-en"
MISSING=0
for f in encoder_model.ort decoder_model_merged.ort tokenizer.bin; do
    if [ -f "$MODEL_DIR/$f" ]; then
        echo "  ✓ $f found ($(du -h "$MODEL_DIR/$f" | cut -f1))"
    else
        echo "  ✗ $f MISSING"
        MISSING=1
    fi
done

if [ $MISSING -eq 1 ]; then
    echo ""
    echo "  ⚠ Model files not found. To download:"
    echo "    pip install moonshine-voice"
    echo "    python -m moonshine_voice.download --language en"
    echo "    Then copy the files to: $MODEL_DIR/"
fi

echo ""
echo "=== Setup complete ==="
echo "Next steps:"
echo "  1. Copy model files to $MODEL_DIR/ (if not done)"
echo "  2. Open project in Android Studio"
echo "  3. Sync Gradle → Build → Run"

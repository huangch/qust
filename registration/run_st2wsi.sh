#!/bin/bash
# run_st2wsi.sh — Run ST2WSI_Registration headlessly via Fiji

set -euo pipefail

# --- Configuration ---
FIJI="${FIJI_PATH:-/opt/Fiji.app/ImageJ-linux64}"

# --- Defaults ---
OUTPUT_DIR=""
REF_IMAGE=""
TGT_IMAGE=""
REF_SERIES=3
TGT_SERIES=3
REF_FLIPPED=false
REF_ROTATED=90
TGT_CHANNEL="Hematoxylon"
TGT_STAIN="H&E"

usage() {
    cat <<EOF
Usage: $0 [options]

Required:
  -o, --output-dir    DIR     Output directory for transformation files
  -r, --ref-image     FILE    DAPI reference image (OME-TIFF)
  -t, --tgt-image     FILE    H&E target WSI

Optional:
  --ref-series        INT     Series index in reference image (default: $REF_SERIES)
  --tgt-series        INT     Series index in target image (default: $TGT_SERIES)
  --ref-flipped               Horizontally flip the reference image (default: $REF_FLIPPED)
  --ref-rotated       DEG     Rotation of reference in degrees: 0, 90, 180, 270 (default: $REF_ROTATED)
  --tgt-channel       NAME    Colour deconvolution channel: Hematoxylon, Eosin, Residual (default: $TGT_CHANNEL)
  --tgt-stain         NAME    Colour deconvolution vectors: 'H&E' or 'H&E 2' (default: $TGT_STAIN)
  --fiji              PATH    Path to Fiji ImageJ-linux64 binary (default: \$FIJI_PATH or $FIJI)
  -h, --help                  Show this help message
EOF
    exit 1
}

# --- Parse arguments ---
while [[ $# -gt 0 ]]; do
    case "$1" in
        -o|--output-dir)   OUTPUT_DIR="$2"; shift 2 ;;
        -r|--ref-image)    REF_IMAGE="$2";  shift 2 ;;
        -t|--tgt-image)    TGT_IMAGE="$2";  shift 2 ;;
        --ref-series)      REF_SERIES="$2"; shift 2 ;;
        --tgt-series)      TGT_SERIES="$2"; shift 2 ;;
        --ref-flipped)     REF_FLIPPED=true; shift ;;
        --ref-rotated)     REF_ROTATED="$2"; shift 2 ;;
        --tgt-channel)     TGT_CHANNEL="$2"; shift 2 ;;
        --tgt-stain)       TGT_STAIN="$2"; shift 2 ;;
        --fiji)            FIJI="$2"; shift 2 ;;
        -h|--help)         usage ;;
        *) echo "Unknown option: $1"; usage ;;
    esac
done

# --- Validate required args ---
[[ -z "$OUTPUT_DIR" ]] && { echo "Error: --output-dir is required."; usage; }
[[ -z "$REF_IMAGE"  ]] && { echo "Error: --ref-image is required.";  usage; }
[[ -z "$TGT_IMAGE"  ]] && { echo "Error: --tgt-image is required.";  usage; }

[[ -f "$FIJI" ]] || { echo "Error: Fiji binary not found at '$FIJI'. Set FIJI_PATH or use --fiji."; exit 1; }
[[ -f "$REF_IMAGE" ]] || { echo "Error: Reference image not found: $REF_IMAGE"; exit 1; }
[[ -f "$TGT_IMAGE" ]] || { echo "Error: Target image not found: $TGT_IMAGE"; exit 1; }

mkdir -p "$OUTPUT_DIR"

# --- Run ---
echo "Running ST2WSI_Registration..."
echo "  Output dir  : $OUTPUT_DIR"
echo "  Reference   : $REF_IMAGE (series=$REF_SERIES, flipped=$REF_FLIPPED, rotated=$REF_ROTATED)"
echo "  Target      : $TGT_IMAGE (series=$TGT_SERIES, channel=$TGT_CHANNEL, stain=$TGT_STAIN)"

# The plugin splits its argument string on whitespace, so a stain name
# containing a space has to reach it bracketed.
PLUGIN_ARG="outputDir=$OUTPUT_DIR \
refImagePath=$REF_IMAGE \
tgtImagePath=$TGT_IMAGE \
refSeries=$REF_SERIES \
tgtSeries=$TGT_SERIES \
refFlipped=$REF_FLIPPED \
refRotated=$REF_ROTATED \
tgtChannel=$TGT_CHANNEL \
tgtStain=[$TGT_STAIN]"

ST2WSI_ARG="$PLUGIN_ARG" \
"$FIJI" --headless --ij2 \
    --run "st2wsi_registration.ST2WSI_Registration"

echo "Done. Results saved to: $OUTPUT_DIR"

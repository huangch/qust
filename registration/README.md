# ST2WSI Registration (Fiji plugin)

Registers the DAPI image of a spatial transcriptomics (ST) slide to the H&E whole slide image (WSI) of the same
section, so that the single-cell coordinates of the ST data can be mapped onto the H&E image. It is the first step of
the [QuST](../README.md) workflow for 10x Xenium data (Fig. 2 of the paper).

1. **Colour deconvolution** extracts the haematoxylin channel of the H&E image, a nuclear stain comparable to DAPI.
2. **SIFT affine alignment** (MPICBG) gives a coarse registration; RANSAC rejects wrong feature matches.
3. **B-spline elastic registration** ([bUnwarpJ](https://imagej.net/plugins/bunwarpj)) refines it, which compensates
   for the deformation of the tissue between the two modalities and for the stitching artefacts of the DAPI image.

## Build and install

Requires JDK 17+ and Maven.

```bash
cd registration
mvn clean package
cp target/ST2WSI_Registration-*.jar /path/to/Fiji.app/plugins/
```

Restart Fiji. The command is then available as **Plugins → Registration → ST2WSI Registration**. Fiji provides
Bio-Formats, bUnwarpJ and MPICBG.

## Use

### In Fiji

Open the DAPI image and the H&E image in Fiji (File → Import → Bio-Formats), start the plugin, choose the two images,
the pyramid series to use (a lower resolution makes it faster), the rotation or flip of the DAPI image, and the output
folder. Put the output folder in the Xenium output folder of the sample: QuST reads the result from there.

### Headless

```bash
./run_st2wsi.sh -o /data/xenium_sample -r /data/xenium_sample/morphology_focus/ch0000_dapi.ome.tif \
                -t /data/he_image.ome.tif --ref-series 3 --tgt-series 3 --ref-rotated 90
```

`FIJI_PATH` (or `--fiji`) points to the `ImageJ-linux64` launcher of Fiji. Run `./run_st2wsi.sh -h` for all options.
The same arguments can be given to the plugin directly:
`ST2WSI_ARG="outputDir=... refImagePath=... tgtImagePath=..." ImageJ-linux64 --headless --ij2 --run st2wsi_registration.ST2WSI_Registration`.

| Argument | Meaning | Default |
|---|---|---|
| `outputDir` | folder receiving the results | |
| `refImagePath` | DAPI image (OME-TIFF) | |
| `tgtImagePath` | H&E image | |
| `refSeries`, `tgtSeries` | series (pyramid level, counted from 1) used of each file | 3 |
| `refFlipped` | the DAPI image has to be flipped horizontally | `false` |
| `refRotated` | rotation of the DAPI image in degrees (-270 … 270) | `90` |
| `tgtChannel` | channel of the colour deconvolution: `Hematoxylon`, `Eosin`, `Residual` | `Hematoxylon` |
| `tgtStain` | stain vectors: `H&E` or `H&E 2` (bracket the name: `tgtStain=[H&E 2]`) | `H&E` |
| `maxEpsilon`, `rod`, `minInlierRatio`, `minNumInliers` | SIFT matching and RANSAC thresholds | 25, 0.92, 0.05, 7 |

If a slide fails with "Failed to find reliable transformation model", try a series of higher resolution, the other
stain vectors (`tgtStain=[H&E 2]`), or looser RANSAC thresholds.

### Output

| File | Content |
|---|---|
| `registration_params.json` | image sizes, scales, flip/rotation, pixel size and the SIFT affine matrix |
| `direct_transf.txt` | the B-spline coefficients of the elastic registration |
| `alignment_sift_{stack.tif,overlay.png}` | quality control of the affine step |
| `alignment_bunwarpj_{stack.tif,overlay.png}` | quality control of the elastic step |

QuST's **10x Xenium Annotation** reads `registration_params.json` and `direct_transf.txt` from the Xenium folder.
Check the overlays before loading the data.

## Licence

GPL-3.0-or-later (see [LICENSE](LICENSE)). The registration builds on bUnwarpJ (Ignacio Arganda-Carreras and Jan
Kybic) and the SIFT implementation of MPICBG (Stephan Saalfeld).

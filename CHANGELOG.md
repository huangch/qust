# Changelog

## 0.6.0

First release of the cleaned-up code base. It contains what the paper describes and nothing else.

- New: the dialogs of the commands that ask for a folder or a file (the Xenium, CosMx and Visium loaders and their registration
  parameters, the exports for classification and segmentation, ST2WSI Registration, the custom Cellpose model) have a **Browse...**
  button next to the text field. QuPath's parameter dialog has no such parameter, so the button is added to the dialog after it is
  shown (`BrowseButtons`); if a QuPath version builds its dialog differently, the plain text field is still there.

- New: **ST2WSI Registration** (Preparation menu): the DAPI-to-H&E registration of the Fiji plugin as a QuST command, so
  that Fiji is not needed. It follows the Fiji plugin step by step with the same parameters and defaults (series, rotation,
  flip, colour deconvolution vectors and stain, denoising, all SIFT and bUnwarpJ parameters): series pulldown lists for
  both images (the target list holds the resolution levels of the open image), the Xenium directory of the Xenium loader
  (the DAPI image is found in it and the results are written into it), an overwrite question, and a *List the series only*
  option. For the same input images it writes the same `registration_params.json` and `direct_transf.txt` as the Fiji
  plugin, byte for byte (tested on a Xenium slide). SIFT (MPICBG) and bUnwarpJ are bundled in the extension jar.
- Fixed (Fiji plugin): the `alignment_bunwarpj` images showed the SIFT-only result, because the return value of bUnwarpJ's
  `applyTransformationMT` was ignored; they now show the elastic result. `applyTransformationMT` also fails with an odd
  image height on a computer with many processors (negative block height); both the plugin and the command work around it.
- New: the path preferences of QuST (script, model and Python environment folders, the CCI dataset file) have a folder or file
  chooser in the preference pane.
- Changed: **Normalize Transcripts** reads the values by position and removes earlier results in one call, which makes it about
  a third faster; the cells are normalized in parallel.
- Note: StarDist runs on the GPU only in a QuPath built with OpenCV and CUDA (`-Pcuda-redist`); the QuPath of the
  `qupath-docker` project (Docker image and native QuPath 0.5.1) is built that way (a synthetic 2000 × 2000 px tile: 2.3 s
  on an A100 against 8.1 s on the CPU, same detections).
- Fixed: the **Delaunay** neighbours linked the cells of separate annotations (separate pieces of tissue) across the gap
  between them, and used the cell centroid where QuPath's own connections use the nucleus centroid. **Delaunay
  Connections** and the neighbour-based analyses now triangulate each annotation on its own, with nucleus centroids. The
  smoke test checks that no link crosses from one annotation to another.
- Fixed: **Simple Tissue Detection** drew long horizontal lines across the tissue and left out parts of it (the code of
  QuPath's command traced the regions with ImageJ's wand and combined them with Java's `Area`). The regions are now
  traced with QuPath's contour tracing and refined with JTS, the smoothing no longer shrinks the outline, and the default
  is one annotation per piece of tissue (`singleAnnotation` is off), because QuPath draws a multi-piece annotation with
  extra horizontal lines. Tested on a real H&E slide.
- New: **Cellpose Nucleus Detection** (Preparation menu), an alternative to StarDist that calls the BIOP Cellpose extension
  by reflection (extension 0.12.1 for QuPath 0.7 and later, 0.10.2 for QuPath 0.5.x).
- Also builds for QuPath 0.5.1 (`-PqupathVersion=0.5.1 -PjavaVersion=17`, Java 17); the smoke test passes in QuPath 0.5.1
  (including StarDist with the StarDist extension 0.5.0), which is the version that starts on hosts with glibc older than
  2.34. The neighbours of a cell now come from `DelaunayTools` in every QuPath version.
- Builds against the published QuPath 0.7 (`./gradlew build`), no local QuPath build or sibling extension is needed.
  Tested in QuPath 0.8.0-SNAPSHOT (see `qupath-extension-qust/tools/smoke.sh`). Needs Java 25, like QuPath.
- The extension jar is self-contained (it bundles the Parquet reader for Xenium and the other libraries QuPath does not
  provide). JavaFX and QuPath's own libraries are not bundled.
- New: **Normalize Transcripts** (Preparation menu): library-size normalization (default total 100, then `log(1 + x)`)
  of the `transcript:<gene>` counts into `transcript-norm:<gene>`. The raw counts are not changed, so tools that expect
  raw counts (such as the HERaLD extension) keep working. **Cell-Cell Interaction Analysis** has a new *Expression*
  option to use the normalized values (results `cci-norm:...`).
- New: the documentation (`docs/`, Sphinx; Read the Docs configuration in `.readthedocs.yaml`).
- New: `registration/`: the Fiji plugin that aligns the DAPI image to the H&E image (SIFT affine + bUnwarpJ B-spline).
  It writes `registration_params.json` and `direct_transf.txt`, which **10x Xenium Annotation** reads.
- New `python/`: training script (`train.py`) and inference script (`classification.py`) of the deep learning module,
  with a `requirements.txt` that works.
- Fixed: **Cell Spatial Profiling by Classification** found no boundary cells when an annotation was selected and
  "Profiling by" was set to `detection`, because the opposite classes were collected with the default instead of the
  chosen setting.
- Fixed: `classification.py` needed a GPU. Without one it retried for about 18 hours. It now uses the CPU, and only
  retries when the GPU is out of memory.
- Fixed: the stain matrix of **Export Images for Region Segmentation** was estimated on RGB instead of optical density
  (as the cell export does).
- Fixed: **Export Images for Region Segmentation** did not accept an annotation as its parent object, and skipped
  annotations without child objects.
- Fixed: **Pseudo Spot Generation** numbered the columns on and on across rows; it now starts every row at 0. The
  "vendor" option, which did nothing, is gone.
- Changed: **Density-based Cell Function Enrichment** is now **Cell Spatial Density** (it computes a kernel density of the
  chosen cells, writes `csd:<ID>`, and no longer needs the Delaunay connections it never used).
- Changed: the neighbour-based analyses (profiling, composition, cell-cell interaction) no longer need QuPath's deprecated
  *Delaunay cluster features 2D* to be run first: they use the Delaunay triangulation of the cell centroids that the QuPath
  hierarchy provides. This also removes the dependence on OpenCV natives.
- New: **Delaunay Connections** (Preparation menu) replaces that deprecated command: it stores the connections of the
  cells of the selected annotations in the image data, with an optional distance threshold and a same-class limit.
  The analyses use stored connections when present, and QuPath's *Show connections* overlay displays them.
- Kept: **Simple Tissue Detection** (the "Pete's Simple Tissue Detection" of earlier releases, QuPath's own *Simple tissue
  detection* by Pete Bankhead, which QuPath now lists under *Deprecated*).
- Changed: **StarDist Nucleus Detection** (the first step of the workflow) is kept. It calls the QuPath StarDist
  extension by reflection, so QuST needs no copy of it to build, and it no longer fails when the model folder is
  empty.
- Changed: both image exports write a folder of images only. The WebDataset and HDF5 formats (nothing in this
  repository reads them) and the bundled native HDF5 library are gone.
- Removed (not part of the paper): AI-DIA annotation, CUDA Delaunay,
  neighborhood-based function enrichment, the DJL (Java) classifier, the OMERO CSV and H5AD exports,
  the QuST-Copilot (LLM) commands and the experimental Xenium loader variants.
- The licence is GPL-3.0-or-later, which is what the source headers (and the QuPath and bUnwarpJ code they build on)
  already said.

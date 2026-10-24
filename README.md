# QuST

**QuST** is a [QuPath](https://qupath.github.io) extension for integrative analysis of whole slide images (WSI) and
single-cell spatial transcriptomics (ST). It is described in

> Huang C-H, Lichtarge S, Fernandez D. *Integrative whole slide image and spatial transcriptomics analysis with QuST and
> QuPath.* npj Precision Oncology 9, 70 (2025). <https://doi.org/10.1038/s41698-025-00841-9>

![QuST workflow](docs/_static/workflow.png)

| Folder | Content |
|---|---|
| [`qupath-extension-qust/`](qupath-extension-qust) | the QuPath extension (Java, Gradle) |
| [`registration/`](registration) | Fiji plugin aligning the ST DAPI image to the H&E image (SIFT + B-spline) |
| [`python/`](python) | training and inference scripts of the deep learning module |
| [`data/cci/`](data/cci) | ligand-receptor table of the cell-cell interaction analysis |
| [`docs/`](docs) | the documentation (Sphinx; one page per component described in the paper) |
| [`DEVELOPING.md`](DEVELOPING.md) | the developer guide (build, test, release) |

## What it does

Everything is under **Extensions → QuST Analysis Toolbox**.

| Menu | Command | Paper |
|---|---|---|
| Import | 10x Xenium Annotation (+ Image Registration Parameters), NanoString CosMx Annotation, 10x Visium Annotation (+ Image Registration Parameters) | single-cell level ST in the H&E image, registration |
| Preparation | Simple Tissue Detection | threshold-based tissue annotation (QuPath's *Simple tissue detection*, which QuPath now lists as deprecated) |
| | ST2WSI Registration | registers the DAPI image of a Xenium slide to the open H&E image (SIFT + B-spline), no Fiji needed |
| | StarDist Nucleus Detection | cell detection on the H&E image (needs the QuPath StarDist extension) |
| | Cellpose Nucleus Detection | the same with Cellpose (needs the BIOP Cellpose extension, a Python with Cellpose, and a QuPath project) |
| | Delaunay Connections | connect the cells to their Delaunay neighbours, with an optional distance and class limit |
| | Normalize Transcripts | library-size normalization of the counts (writes `transcript-norm:<gene>`, raw counts unchanged) |
| | Pseudo Spot Generation | Visium-like spots from single-cell data |
| Analysis | Cell Spatial Profiling by Classification / by Measurement | distance of each cell to the border of a cluster (Algorithm 1) |
| | Neighboring Cell Type Composition | cellular neighbourhood analysis (Algorithm 2) |
| | Cell-Cell Interaction Analysis | ligand-receptor products between neighbours (Algorithm 3) |
| | Cell Spatial Density | kernel density of the cells of a type (Fig. 1b, cell-subtype spatial density comparison) |
| | DBSCAN-CellX | density-based clustering of the cells of chosen classes |
| Classification and Segmentation | Export Images for Object Classification / Region Segmentation | training sets for H&E-based models |
| | Object Classification, Region Segmentation | apply a trained PyTorch model |

The analyses that look at the neighbours of a cell use the Delaunay triangulation of the cell centroids that QuPath keeps
for the image (the one that *Show connections* displays), so nothing has to be run first. To limit the links by distance
or class, run **Delaunay Connections** (Preparation): the connections it stores are used instead.

## Install

A release has three files: the QuPath extension, the Fiji registration plugin and a zip with the Python scripts and
the ligand-receptor table (`./build-release.sh` builds all three into `dist/`).

Requirements: QuPath 0.7 or later. For the deep learning module and DBSCAN-CellX: Python 3.10 or later (3.11 tested) with
[`python/requirements.txt`](python/requirements.txt); a GPU is recommended for training but not required.

1. Download `qupath-extension-qust-<version>.jar` from the [releases](https://github.com/huangch/qust/releases) and drop
   it onto the QuPath window (or copy it to the QuPath extensions folder). Restart QuPath.
2. For Xenium data, run **Preparation → ST2WSI Registration** in QuPath. Only to register in Fiji instead, copy
   `ST2WSI_Registration-<version>.jar` to the `plugins` folder of Fiji and restart Fiji
   (see [`registration/README.md`](registration/README.md)).
3. Unzip `qust-python-and-data-<version>.zip`. For the Python-based commands, create an environment and install the
   requirements:

   ```bash
   python -m venv qust-env && source qust-env/bin/activate
   pip install -r python/requirements.txt
   ```

4. Open **Edit → Preferences → QuST** and set
   - *QuST script directory*: the `python` folder of this repository,
   - *QuST environment type* and *environment name or directory*: how to start the environment of step 2 (for a
     `venv`/conda environment its folder or name; for *Python Executable* the path of `python`),
   - *CCI dataset file*: `data/cci/human_lr_pair.csv`,
   - *StarDist model directory* (only for **StarDist Nucleus Detection**, which also needs the
     [StarDist extension](https://github.com/qupath/qupath-extension-stardist) of QuPath): the folder with the `.pb` models,
   - *Object Classification / Region Segmentation model directory*: where the trained models are kept.

## Build

```bash
./build-release.sh        # extension + Fiji plugin + python/data zip into dist/ (JDK 25, JDK 17+, Maven, zip)
```

Or each part alone: `cd qupath-extension-qust && ./gradlew build` (JDK 25; writes
`build/libs/qupath-extension-qust-<version>.jar`) and `cd registration && mvn clean package`.

The extension jar bundles what QuPath does not provide (Parquet reader, OpenCSV, ...) and is about 70 MB. Another QuPath
release can be targeted with `-PqupathVersion=<version>` (for QuPath 0.5.x also `-PjavaVersion=17`, with JDK 17).

## Test

`qupath-extension-qust/tools/smoke.sh` starts a real QuPath with the extension on a synthetic slide and checks the
menus and the commands below. It needs a QuPath distribution (`QUPATH_APP=<QuPath>/lib/app`), `xvfb-run`, and, for the
Python part, `QUST_PYTHON=<python of an environment with python/requirements.txt>`.

- covered: the menu, Normalize Transcripts, Neighboring Cell Type Composition, Cell Spatial Profiling by Classification,
  Cell-Cell Interaction Analysis, Cell Spatial Density, Pseudo Spot Generation, both image exports, `train.py`, Object
  Classification, DBSCAN-CellX, Region Segmentation
- not covered by an automatic test: the Xenium, CosMx and Visium loaders, the registration plugin of Fiji (it was run once on an
  image pair, see its README; the QuPath command ST2WSI Registration is tested on a synthetic pair and was run on a real
  Xenium sample), the detection step of Cellpose Nucleus Detection (it needs a project and the Cellpose model
  weights; the test only checks that the command finds the extension and stops cleanly without a project), and the
  detection step of StarDist Nucleus Detection (the test, with `STARDIST_JAR` and `STARDIST_MODELS`, runs StarDist on
  the synthetic slide, which has no nuclei, so only the loading and running of the model is checked)

## Citing and licence

Please cite the paper above and the [QuPath paper](https://qupath.readthedocs.io/en/stable/docs/intro/citing.html).
DBSCAN-CellX is by Küchenhoff et al. (*Sci Rep* 13, 18868, 2023). The ligand-receptor table is CellTalkDB (Shao et al.,
*Brief Bioinform* 22, bbaa269, 2021).

GPL-3.0-or-later, see [LICENSE](LICENSE). The code builds on QuPath and on bUnwarpJ (Arganda-Carreras and Kybic), which
are GPL-licensed.

Loading spatial transcriptomics data
====================================

QuST focuses on single-cell level analysis. It loads the single-cell ST formats of 10x Genomics Xenium and NanoString CosMx
and, for whole-slide full-spectrum ST analysis, the 10x Genomics Visium datasets, into QuPath (paper, *Integrative WSI and ST
analysis at single-cell level*, Fig. 1a). The ST data are attached to the cells that were detected on the H&E image, so
every cell has both its morphology and its expression.

Every command is under **Extensions → QuST Analysis Toolbox**. Unless stated otherwise, a command works on the **selected
annotations that contain detection objects** (cells). In the dialogs, a parameter that asks for a folder or a file has a
**Browse...** button next to its text field; the path can also be typed or pasted.

The workflow is:

1. detect the cells on the H&E image (below);
2. for Xenium, align the ST data to the image (:doc:`registration`); for Visium, give the affine matrix of the Loupe Browser;
3. load the ST data into the detected cells.

Cell detection
--------------

The cells are first detected on the H&E image, for example with StarDist or Cellpose. QuPath's own cell detection can be used
as well; the two commands below are shortcuts to the StarDist and Cellpose extensions. Detect the cells inside an annotation
that covers the tissue.

StarDist Nucleus Detection
~~~~~~~~~~~~~~~~~~~~~~~~~~

Detects the nuclei of the selected annotations with the QuPath StarDist extension (called by reflection; install it
separately). The models are the ``.pb`` files of the *StarDist model directory* preference.

.. list-table::
  :header-rows: 1
  :widths: 24 20 56

  * - Key
    - Default
    - Meaning
  * - ``starDistModel``
    - first model
    - Model (``.pb`` file)
  * - ``threshold``
    - 0.1
    - Probability threshold
  * - ``normalizePercentilesLow``, ``normalizePercentilesHigh``
    - 1, 99
    - Percentile normalization of the image
  * - ``channel``
    - empty
    - Detection channel, for example ``DAPI``
  * - ``cellExpansion``, ``cellConstrainScale``
    - -1 (off)
    - Expand the nuclei to cells (µm), constrain the expansion by the nucleus size
  * - ``includeProbability``, ``measureShape``, ``measureIntensity``
    - false
    - Add the probability, shape and intensity measurements
  * - ``nThreads``, ``tileSize``
    - 0 (StarDist default)
    - Threads and tile size of StarDist

Cellpose Nucleus Detection
~~~~~~~~~~~~~~~~~~~~~~~~~~

Detects the nuclei (or cells) of the selected annotations with the `BIOP Cellpose extension
<https://github.com/BIOP/qupath-extension-cellpose>`_ of QuPath (called by reflection; install it separately: version
0.12.1 or later for QuPath 0.7 and later, 0.10.2 for QuPath 0.5.x). The Python with Cellpose is the one set in the
Cellpose preferences of QuPath (*Cellpose Python path*, and for Cellpose-SAM *Cellpose-SAM Python path*). The extension
keeps temporary files in the QuPath project folder, so the image has to be opened from a **QuPath project**; Cellpose
downloads the model weights the first time a model is used.

.. list-table::
  :header-rows: 1
  :widths: 24 20 56

  * - Key
    - Default
    - Meaning
  * - ``cellposeModel``
    - ``cyto3``
    - ``cyto3``, ``nuclei``, ``cyto2``, ``cyto`` or ``cpsam`` (Cellpose-SAM, Cellpose 4)
  * - ``customModel``
    - empty
    - Path of a custom model; replaces the model above
  * - ``channel``
    - empty
    - Detection channel, for example ``DAPI``
  * - ``diameter``
    - 0 (not set)
    - Expected object diameter in µm
  * - ``cellprobThreshold``, ``flowThreshold``
    - 0, 0.4
    - Cellpose thresholds
  * - ``normalizePercentilesLow``, ``normalizePercentilesHigh``
    - 1, 99
    - Percentile normalization of the image
  * - ``useGPU``
    - true
    - Run Cellpose on the GPU
  * - ``cellExpansion``
    - -1 (off)
    - Expand the nuclei to cells (µm)
  * - ``measureShape``, ``measureIntensity``
    - false
    - Add the shape and intensity measurements
  * - ``simplify``, ``tileSize``
    - 1, 0 (extension default)
    - Simplification distance of the shapes (pixels), tile size

Loading the data
----------------

Select the annotation(s) that contain the detected cells and run one of the commands below. Each cell gets its cell type as
classification and its expression as the measurements ``transcript:<gene>`` (**Measure → Show detection measurements**).
The values are those of the data folder (counts for Xenium).

10x Xenium Annotation
~~~~~~~~~~~~~~~~~~~~~

Attaches the Xenium data to the cells of the selected annotations. Reads ``registration_params.json`` and
``direct_transf.txt`` from the Xenium folder when they exist (:doc:`registration`); the cell positions from
``cells.csv.gz`` or ``cells.parquet``; the cell types from ``analysis/clustering/gene_expression_graphclust/clusters.csv``
(or ``analysis.tar.gz``); the expression from ``cell_feature_matrix``.

.. list-table::
  :header-rows: 1
  :widths: 24 20 56

  * - Key
    - Default
    - Meaning
  * - ``xeniumDir``
    -
    - Xenium output folder
  * - ``dontTransform``
    - false
    - Do not transform the coordinates (the images are already aligned)
  * - ``AffineTransformOnly``
    - false
    - Use the SIFT affine matrix only, not the B-spline refinement
  * - ``removeUnlabeledCells``
    - true
    - Drop Xenium cells without a cluster label
  * - ``inclGeneExpr``
    - true
    - Write the expression as ``transcript:<gene>``
  * - ``inclBlankCodeword``, ``inclUnassignedCodeword``, ``inclDeprecatedCodeword``, ``inclIntergenicRegion``,
      ``inclNegCtrlCodeword``, ``inclNegCtrlProbe``
    - false
    - Also write these feature types
  * - ``maskDownsampling``
    - 2
    - Downsampling used when assigning the transcripts to the cells

NanoString CosMx Annotation
~~~~~~~~~~~~~~~~~~~~~~~~~~~

.. list-table::
  :header-rows: 1
  :widths: 24 20 56

  * - Key
    - Default
    - Meaning
  * - ``cosmxDir``
    -
    - CosMx folder
  * - ``consolToAnnot``
    - false
    - Consolidate the transcripts to Visium-style spots
  * - ``removeUnlabeledCells``
    - true
    - Drop cells without a label
  * - ``inclGeneExpr``
    - true
    - Write the expression as ``transcript:<gene>``
  * - ``inclNegCtrlProbe``
    - false
    - Also write the negative control probes
  * - ``maskDownsampling``
    - 2
    - Downsampling used when assigning the transcripts to the cells

10x Visium Annotation
~~~~~~~~~~~~~~~~~~~~~

.. list-table::
  :header-rows: 1
  :widths: 24 20 56

  * - Key
    - Default
    - Meaning
  * - ``visiumDir``
    -
    - Space Ranger output folder
  * - ``spotDiameter``
    - 65 µm
    - Diameter of the spots
  * - ``format``
    - detection
    - Create the spots as ``detection`` or ``annotation`` objects
  * - ``tissueRegionsOnly``
    - true
    - Only the spots under the tissue
  * - ``loadTranscriptData``
    - false
    - Write the expression of each spot as ``transcript:<gene>``
  * - ``cytAstTransf``, ``siftAffineTransf``, ``bsplineTransf``
    - false
    - Which transformation to apply: CytAssist alignment, SIFT affine matrix, B-spline
  * - ``manualShift``
    - false
    - Apply the manual shift of ``registration_params.json``

Visium needs the affine matrix of the **Visium Image Alignment** function of the Loupe Browser (or the CytAssist alignment);
the command below stores it, with an optional manual scaling and shift, in ``registration_params.json`` of the Visium folder.

10x Visium Image Registration Parameters
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Like the Xenium one, for the Visium folder, with a manual scaling factor and a manual shift in x and y.

10x Xenium Image Registration Parameters
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Writes ``registration_params.json`` in the Xenium folder from values entered by hand (the Fiji plugin writes the same
file automatically). Parameters: the Xenium output directory, the source image width and height, the DAPI image pixel
size, the source and target image scaling factors, flip horizontally/vertically, the rotation, and the SIFT affine
matrix (six numbers).

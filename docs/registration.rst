Registration of ST data to the H&E image
========================================

In Xenium and CosMx the cells are localised on the DAPI image, while the whole slide image is H&E: the two modalities come
from different rounds of sample preparation and scanning, so loading the ST data needs an extra step that aligns it with the
H&E image. QuST follows the `guidelines of 10x Genomics
<https://www.10xgenomics.com/analysis-guides/he-to-xenium-dapi-image-registration-with-fiji>`_ and aligns the coordinates of the ST
data to the reference image (paper, Fig. 2):

1. **Colour deconvolution** extracts the haematoxylin channel of the H&E image (the target), a nuclear stain comparable to
   DAPI (the reference).
2. **SIFT affine alignment** (MPICBG) gives a coarse registration; RANSAC rejects wrong feature matches. The result is the
   *SIFT affine matrix*.
3. **B-spline elastic registration** (`bUnwarpJ <https://imagej.net/plugins/bunwarpj>`_) refines it. It compensates for the
   deformation of the tissue between the two modalities and for the grid-like artefacts of the stitched DAPI images. The result
   is the *B-spline transformation function*.

Both map the coordinates of the reference image to the target image. In the experiment of the paper, 723,384 cells were
detected on the H&E image; without registration 122,288 cells had no ST counterpart, with registration 99,405 (an
improvement of 18.71%).

The registration is available in two places, with the same algorithm, parameters and results:

* as the **Fiji plugin** ST2WSI Registration (``registration/`` of the repository), which can also run headless; and
* as the QuST command **Extensions → QuST Analysis Toolbox → Preparation → ST2WSI Registration**, so that Fiji is not needed.

Both write ``registration_params.json`` and ``direct_transf.txt``, which **10x Xenium Annotation** reads from the Xenium
folder (:doc:`loading`).

Series and scaling
------------------

Each image is registered at one *series* (resolution level, counted from 1, 1 is the full resolution); a lower resolution is
faster. Choose series with about the same pixel size in the two images. The scaling factor to the full resolution is
computed from the sizes of the series. For a Xenium morphology image (levels 1, 2, 4, 8, 16, ...) series 5 is 16 times
smaller than the full resolution; for an H&E slide with levels of 1, 4 and 16 times, series 3 is 16 times smaller. The log
lists the series of both images, and tells when the scaling factor of a series is not an integer.

In the QuST command the *Reference image series* is chosen in a pulldown list (1 to 8, default 5), and the *Target image
series* in a pulldown list of the resolution levels of the open image (default: the level closest to a downsample of 16).
The Fiji plugin has the same two pulldown lists.

QuST command
------------

Open the H&E image in QuPath, run the command, and choose the **Xenium directory**. It is the same folder (and the same
preference) as in **10x Xenium Annotation**; the DAPI image is taken from it
(``morphology_focus/morphology_focus_0000.ome.tif``, or ``morphology_focus.ome.tif`` or ``morphology.ome.tif``), and the
results are written into it. Another DAPI image can be given in *DAPI image file*. If the result files exist already, the
command asks whether to overwrite them. Check the overlay images afterwards: ``alignment_sift_overlay.png`` and
``alignment_bunwarpj_overlay.png`` show the H&E image in green and the aligned DAPI image in magenta (agreement is white or
grey).

.. list-table::
  :header-rows: 1
  :widths: 30 16 54

  * - Key
    - Default
    - Meaning
  * - ``xeniumDir``, ``dapiImage``
    -
    - The Xenium folder and, optionally, the DAPI image file
  * - ``refSeries``, ``tgtSeries``
    - 5, level closest to 16
    - The series of the DAPI image (reference) and of the open H&E image (target)
  * - ``refFlipped``
    - false
    - Flip the DAPI image horizontally (after the rotation)
  * - ``refRotated``
    - 90
    - Rotation of the DAPI image (-270, -180, -90, 0, 90, 180, 270 degrees) that brings it to the orientation of the H&E image
  * - ``pxlSz``
    - 0.2125 µm
    - Pixel size of the full resolution DAPI image
  * - ``tgtChannel``, ``tgtStain``
    - Hematoxylon, H&E
    - The stain that is registered (Hematoxylon, Eosin, Residual) and the colour deconvolution vectors (H&E or H&E 2; try the
      other one when too few matches are found)
  * - ``rolling``, ``sigma``
    - 50, 12
    - Denoising: rolling ball radius of the background subtraction and sigma of the Gaussian blur
  * - ``sift_initialSigma``, ``sift_steps``, ``sift_minOctaveSize``, ``sift_maxOctaveSize``, ``sift_fdSize``, ``sift_fdBins``
    - 1.6, 3, 64, 1024, 4, 8
    - SIFT feature extraction
  * - ``rod``, ``maxEpsilon``, ``minInlierRatio``, ``minNumInliers``
    - 0.92, 25, 0.05, 7
    - Matching (ratio of the closest to the next closest match) and RANSAC (largest alignment error, smallest inlier ratio and
      number)
  * - ``bUnwarpJ_mode``, ``img_subsamp_fact``, ``min_scale_deformation``, ``max_scale_deformation``
    - Fast, 0, 0, 3
    - bUnwarpJ registration mode, image subsampling and scales of the deformation
  * - ``divWeight``, ``curlWeight``, ``landmarkWeight``, ``imageWeight``, ``consistencyWeight``, ``stopThreshold``
    - 0, 0, 0, 1, 10, 0.01
    - bUnwarpJ weights and stop threshold
  * - ``verbose``
    - false
    - Also write ``prepared_dapi.png`` and ``prepared_he.png``, the images that SIFT sees (in the Fiji plugin it keeps the
      intermediate windows open)
  * - ``listSeries``
    - false
    - Only write the series of both images into the log and stop

The registration takes about 20 seconds for a Xenium slide with an H&E image of 41,831 × 32,844 px. The RANSAC step is
deterministic for the same images: the QuST command and the Fiji plugin write identical ``registration_params.json`` and
``direct_transf.txt`` for the same input images (tested on a Xenium slide with both). The two read the pyramid levels of a
slide with different libraries (QuPath and Bio-Formats), which can differ by a pixel in the size of a level and slightly in
the pixel values; the result then differs by a pixel or so of the registration resolution.

Fiji plugin
-----------

Build and install (JDK 17 or later and Maven):

.. code-block:: bash

  cd registration
  mvn clean package
  cp target/ST2WSI_Registration-*.jar /path/to/Fiji.app/plugins/

Restart Fiji. The command is **Plugins → Registration → ST2WSI Registration**. Open the DAPI image and the H&E image
(**File → Import → Bio-Formats**), start the plugin, and choose the two images, their series, the rotation or flip of the DAPI
image, the colour deconvolution vectors and the output folder (the Xenium output folder of the sample). Note that the series
of an SVS file in Bio-Formats includes the thumbnail, so its numbers differ from the resolution levels of QuPath.

It can also run headless:

.. code-block:: bash

  ./run_st2wsi.sh -o /data/xenium_sample \
      -r /data/xenium_sample/morphology_focus/morphology_focus_0000.ome.tif \
      -t /data/he_image.ome.tif --ref-series 5 --tgt-series 3 --ref-rotated 90

``FIJI_PATH`` (or ``--fiji``) points to the ``ImageJ-linux64`` launcher of Fiji; ``./run_st2wsi.sh -h`` lists all options. The
plugin takes the parameters of the table above, with the same names (``outputDir``, ``refImagePath`` and ``tgtImagePath``
instead of the Xenium folder, and ``bUnwarpJ_mode`` as a number: 0 Fast, 1 Accurate, 2 Mono), for example ``ST2WSI_ARG="outputDir=... refImagePath=... tgtImagePath=..." ImageJ-linux64 --headless --ij2 --run
st2wsi_registration.ST2WSI_Registration``.

If a slide fails with "Failed to find reliable transformation model", try a series of higher resolution, the other stain
vectors, the other rotation or flip of the DAPI image, or looser RANSAC thresholds.

Output
------

.. list-table::
  :header-rows: 1
  :widths: 38 62

  * - File
    - Content
  * - ``registration_params.json``
    - image sizes, scales, flip/rotation, pixel size and the SIFT affine matrix
  * - ``direct_transf.txt``
    - the B-spline coefficients of the elastic registration
  * - ``alignment_sift_stack.tif``, ``alignment_sift_overlay.png``
    - quality control of the affine step
  * - ``alignment_bunwarpj_stack.tif``, ``alignment_bunwarpj_overlay.png``
    - quality control of the elastic step

Licence
-------

GPL-3.0-or-later. The registration builds on bUnwarpJ (Ignacio Arganda-Carreras and Jan Kybic) and the SIFT implementation
of MPICBG (Stephan Saalfeld).

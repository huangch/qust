Installing
==========

A QuST release has three files:

``qupath-extension-qust-<version>.jar``
  the QuPath extension (about 70 MB: it contains the Parquet reader and the other libraries QuPath does not provide)
``ST2WSI_Registration-<version>.jar``
  the Fiji plugin that registers the ST DAPI image to the H&E image (Xenium data)
``qust-python-and-data-<version>.zip``
  the Python scripts for the deep learning module and the ligand-receptor table

Requirements
------------

- **QuPath 0.7 or later.** The extension is built against QuPath 0.7.0 and was tested in QuPath 0.8.0-SNAPSHOT.
  QuPath 0.5.1 is supported by a second build of the same source (``./gradlew build -PqupathVersion=0.5.1
  -PjavaVersion=17``, Java 17); the smoke test passes there too. Use it where QuPath 0.7 cannot start (see
  *Troubleshooting* below).
- **Python 3.10 or later** (3.11 tested) for the deep learning commands and DBSCAN-CellX. A GPU is recommended to train
  models, not required.
- **Fiji** only to register Xenium data with the Fiji plugin (the registration is also a QuPath command).
- The **QuPath StarDist extension**, only for the StarDist Nucleus Detection command.
- The **BIOP Cellpose extension** and a Python environment with Cellpose, only for the Cellpose Nucleus Detection command.

The QuPath extension
--------------------

Drop ``qupath-extension-qust-<version>.jar`` onto the QuPath window, or copy it to the QuPath extensions folder
(**Edit → Preferences → Extensions**), and restart QuPath. The commands are under **Extensions → QuST Analysis
Toolbox**.

The Python environment
----------------------

Unzip ``qust-python-and-data-<version>.zip``. It contains the folders ``python`` and ``data``. Create an environment and
install the requirements:

.. code-block:: bash

  python -m venv qust-env
  source qust-env/bin/activate
  pip install -r python/requirements.txt

The requirements pin ``numpy<2`` and ``pandas<2`` because the upstream DBSCAN-CellX needs them. If pip finds no wheel
of ``histomicstk`` for your platform, install it with
``pip install histomicstk --find-links https://girder.github.io/large_image_wheels``.

The Fiji plugin
---------------

Copy ``ST2WSI_Registration-<version>.jar`` to the ``plugins`` folder of Fiji and restart Fiji. The plugin is then
**Plugins → Registration → ST2WSI Registration** (see :doc:`registration`).

Preferences
-----------

Open **Edit → Preferences → QuST**.

.. list-table::
  :header-rows: 1
  :widths: 32 68

  * - Preference
    - Value
  * - QuST script directory
    - the ``python`` folder (it has ``classification.py``)
  * - QuST environment type
    - how the Python environment is started: *Anaconda or Miniconda* (``conda activate``), *Python venv*, or *Python
      Executable* (the ``python`` of the environment is called directly)
  * - QuST environment name or directory
    - the name of the conda environment, the folder of the venv, or the path of the ``python`` executable
  * - CCI dataset file
    - ``data/cci/human_lr_pair.csv``, or another table with the ligand symbol in the second and the receptor symbol in
      the third column
  * - Object Classification model directory
    - folder with the trained cell classifiers (``<name>.pt``)
  * - Region Segmentation model directory
    - folder with the trained region classifiers (``<name>.pt``)
  * - StarDist model directory
    - folder with the StarDist models (``.pb``)
  * - Default image file format
    - format of the exported training images (``png``)
  * - Default sample size for H&E stain normalization
    - number of image patches used to estimate the stain matrix (100). The exports need at least this many objects

Building from source
--------------------

See ``DEVELOPING.md`` in the repository.

Troubleshooting
---------------

*PyTorch does not see the GPU (torch.cuda.is_available() is False)*
  The newest PyTorch on PyPI is built for CUDA 13 and needs an NVIDIA driver 580 or newer (``nvidia-smi`` shows the
  driver). Update the driver, or, with an older driver (570 is CUDA 12.8), install a CUDA 12.8 build in the QuST
  environment: ``pip install torch==2.8.0 torchvision==0.23.0``. Without a GPU, **Object Classification** and
  **Region Segmentation** run on the CPU, slowly.

*StarDist is slow (runs on the CPU)*
  StarDist runs through QuPath's OpenCV, which only uses the GPU if QuPath was built with OpenCV and CUDA (QuPath's
  Gradle option ``-Pcuda-redist``; the official QuPath downloads are CPU-only). The QuPath of the ``qupath-docker``
  project is built that way, and QuPath logs ``CUDA detected and will be used if possible`` at start. A CUDA 13 build
  (QuPath 0.7 and later) needs an NVIDIA driver 580 or newer; the CUDA 11.8 build of QuPath 0.5 also works with older drivers.

*"Missed selected annotations"*
  Select the annotation that contains the detected cells (cells have to be its children in the hierarchy).

*The neighbour-based analyses give odd results at the borders of the tissue*
  The Delaunay triangulation links cells over any distance inside an annotation, including across a gap or a hole in
  it. To limit the links, run **Delaunay Connections** (Preparation menu) first with a distance threshold: QuST uses the
  connections it stores. One annotation per piece of tissue also keeps the pieces apart.

*Object Classification or Region Segmentation: "No pixel size information"*
  Set the pixel size of the image in QuPath (**Image** tab).

*Object Classification or Region Segmentation: "No model exist in the model directory"*
  The model directory of the preferences has no ``<name>.pt``. Models are written by ``train.py`` as
  ``<name>.final.pt``.

*The Python commands fail at once*
  Run the command printed in the QuPath log ("Executing command") in a terminal. It shows the Python error.

*Export of images: "The number of objects is smaller than the number required for normalization"*
  The annotation has fewer objects than the *sample size for H&E stain normalization* preference. Lower the preference
  or turn the normalization off.

*StarDist Nucleus Detection: "The QuPath StarDist extension is not installed"*
  Install the `StarDist extension <https://github.com/qupath/qupath-extension-stardist>`_ of QuPath.

*StarDist Nucleus Detection: "no jniopenblas_nolapack in java.library.path" (UnsatisfiedLinkError)*
  Not a QuST problem: QuPath itself could not load its OpenCV libraries, which StarDist (and QuPath's cell detection and
  pixel classifiers) use. The OpenCV/OpenBLAS libraries shipped with QuPath 0.7 and later need glibc 2.34 or later on
  Linux (``ldd --version``; Red Hat 8 and similar systems have 2.28). Run QuPath on a newer system or in a container
  (for example Ubuntu 22.04 or later), or use an older QuPath release together with its matching extensions.
  The ``Dockerfile`` of the ``qupath-docker`` project (Ubuntu 22.04 with CUDA, QuPath, StarDist, Fiji, and a conda
  environment ``qust`` with the Python requirements) takes the QuST files from ``dist/`` (run ``build-release.sh``
  first) and sets the script and CCI-dataset preferences to ``/app/qust/python`` and ``/app/qust/data/cci``.

Cellular spatial profiling
==========================

Cell spatial profiling is the foundation of the other spatial analyses of QuST (paper, *Cellular spatial profiling*, Fig. 5).
First, a **Delaunay clustering** gives the connectivity of the neighbouring cells; then the edge distance of each chosen cell
is computed. The position of each cell in its cluster is the input of the following analyses. A use case is the spatial
profiling of the tumour micro-environment: the heat map of the distance of a cancer epithelial cell to the boundary of its
tumour shows the differential gene expression of the intratumoral tumour cells and of the tumour cells at the
immune-invasive surface.

Neighbours
----------

The commands that use neighbours (profiling, composition, interaction) use the Delaunay triangulation of the nucleus centroids
of the cells of each annotation (the cell centroid for cells without a nucleus; cells of different annotations are never
linked). No other command has to be run first. To limit the links by distance or class, run **Delaunay Connections**: the
connections it stores are used instead. *No. of layers* in the commands is the number of neighbour steps on the Delaunay graph.

Delaunay Connections
~~~~~~~~~~~~~~~~~~~~

Connects each cell of the selected annotations to its neighbours in the Delaunay triangulation of the nucleus centroids
(the cell centroid for cells without a nucleus) and stores the connections in the image data. Each annotation is
triangulated on its own, so separate pieces of tissue are not linked across the gap between them; annotations with fewer
than three cells are skipped. The neighbour-based analyses read the connections (:doc:`neighborhood`), and QuPath's
*Show connections* overlay displays them. Without this command the analyses use the same triangulation without a
distance or class limit.

.. list-table::
  :header-rows: 1
  :widths: 28 16 56

  * - Key
    - Default
    - Meaning
  * - ``maxDistance``
    - 0 (none)
    - Drop links between centroids that are further apart than this (µm; pixels if the pixel size is unknown)
  * - ``sameClass``
    - false
    - Drop links between cells of different classes

The log tells how many cells were connected by how many links.

Algorithm
---------

The algorithm (paper, Algorithm 1) takes the cells of a chosen cell type *t* as the cluster, and a neighbourhood defined by an
edge distance *d* (a number of layers). The cells of the type *t* that have neighbours of another type are at the
border of the cluster and get the value 1; the cells next to those get 2, and so on, until every cell of
the cluster has a value. The value is the distance of the cell to the boundary of its cluster, in layers of cells.

Cell Spatial Profiling by Classification
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

The cluster is the set of target classes. Target cells next to cells of another class get the value
1, the cells next to those get 2, and so on.

.. list-table::
  :header-rows: 1
  :widths: 24 20 56

  * - Key
    - Default
    - Meaning
  * - ``tgtCls``
    - the selected classes
    - Target class(es), comma-separated
  * - ``type``
    - ``annotation`` when annotations (and no cells) are selected, else ``detection``
    - ``detection``: the class of the cell; ``annotation``: the class of the annotation containing the cell
  * - ``id``
    - untitled
    - Name of the profile (the measurement is ``csp:cls:<id>``)
  * - ``layers``
    - 0
    - Maximal number of layers (0: all)

The result is the measurement ``csp:cls:<id>`` of the cells of the cluster. Display it with **Measure → Show measurement
maps** to get the heat map of Fig. 5b.

Pseudo spot generation
======================

Sub-cellular ST technologies cover a limited range of the genome, so lower-resolution technologies that analyse the entire
genome are still widely used, and data are needed to evaluate gene expression deconvolution approaches. QuST mimics the
Visium datasets from single-cell data (paper, *Pseudo spot generation based on single cell ST data*, Fig. 9): a grid of
Visium-like spots is laid over a region of interest, and the single cells inside a spot are the single-cell ground truth of
that spot, with which spatial single-cell deconvolution methods can be evaluated.

Pseudo Spot Generation
----------------------

Lays a grid of Visium-like spots over the selected annotations, as annotation objects with the class
``<prefix>-<row>-<column>``.

.. list-table::
  :header-rows: 1
  :widths: 24 20 56

  * - Key
    - Default
    - Meaning
  * - ``spotDiameter``
    - 55 µm
    - Diameter of a spot
  * - ``minSpotDist``
    - 100 µm
    - Distance between the centres of neighbouring spots
  * - ``rectGridArrangement``
    - false
    - Rectangular grid (default: hexagonal)
  * - ``rectShapeSpot``
    - false
    - Square spots (default: round)
  * - ``prefix``
    - pseudo-spot
    - Prefix of the class of the spots

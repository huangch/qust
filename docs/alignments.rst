.. qust documentation master file, created by
   sphinx-quickstart on Sat Sep 21 13:44:35 2024.
   You can adapt this file completely to your liking, but it should at least
   contain the root `toctree` directive.

:Authors:
    Chao-Hui Huang

:Version: 1.0 of 2024/10/24
:Dedication: To Everyone contribution to QuST

Load H&E Images into FIJI
=========================

First, click "File -> Import -> Bio-Formats" and select the corresponding H&E image file.

.. image:: artifacts/fiji-file-import-bioformats.png
   :width: 400pt


In Bio-Formats Import Options, using options as showing below.

.. image:: artifacts/fiji-bioformats-import-options.png
   :width: 400pt


Next, select the Series Number. The higher series number, e.g., Series 1, the higher resolution. However, higher resolution may result OOM (out-of-memory) if the resource is limitied. On the other hand, lower resolution may limit the accuracy of the following preocess. To author's experience, Series 3 is usually a good balance.

.. image:: artifacts/fiji-bioformats-series-options.png
   :width: 400pt


Click Ok button, the chosen H&E whoile slide image will be loas as a RGB stack in FIJI. Note that in this example, we used `FFPE Human Breast with Custom Add-on Panel
provided by 10x Genomics
<https://www.10xgenomics.com/datasets/ffpe-human-breast-with-custom-add-on-panel-1-standard>`_. Currently, 10x Genomics has performmed image registration for all of the H&E images in their database. Thus, if the user is using their image as the example, some unexpected noises (e.g., black strips at the edges of the H*E image) have to be removed beforehand.

.. image:: artifacts/fiji-wsi-he-stack.png
   :width: 400pt
 
The type of the loaded H&E image is RGB stack. It is necessary to convert the type to RGB Color by selecting Image->Type->RGB Color.

.. image:: artifacts/fiji-image-type-rgb-color.png
   :width: 400pt



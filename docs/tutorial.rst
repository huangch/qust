.. _tutorial:

TUTORIAL
========

.. contents:: Table of Contents
   :depth: 2
   :local:

Tutorial Prerequisites
----------------------

- The images you wish to analyze (Xenium as well as H&E)
- A folder containing the transcriptomic information for each Xenium image
- Annotations for each Xenium data (.csv file)

Create a new QuPath project
---------------------------

If you have any issues creating a project, feel free to reference this tutorial.

Image Registration
------------------

To perform Image Registration, follow the official tutorial provided by 10X here. If you have any questions, see Chao-Hui Huang (Chao-Hui.Huang@pfizer.com).

Sample prep
-----------

Tissue and Nucleus Segmentation
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~

Download ``tiss_nucl_dtxn.groovy`` to your project directory. Then go to ``Automate`` --> ``Script Editor`` --> ``File`` --> ``Open`` --> and select ``tiss_nucl_dtxn.groovy`` and run it. Do this for each image in your project (Xenium and H&E).

If the tissue selection is unsatisfactory, you can look at the commands in the script and perform the corresponding GUI operations manually, specifically playing around with the “232” threshold value for Pete’s Simple Tissue Detection until the annotation is satisfactory.

Loading Xenium Annotations
--------------------------

For each of the Xenium images, perform the following steps:

1. Click ``Extensions`` --> ``QuST analysis toolbox`` --> ``10X Xenium Annotation``.
2. Select the Xenium directory for the image you are working on and click ``Run``.
3. Under “Annotations” select a class and right-click to ``Populate from Existing Objects``.
4. Save the annotation file you want to use to your project directory. Go to ``Automate`` --> ``Script Editor`` --> ``File`` --> and ``Open`` that file.
5. In this script, be sure to comment/uncomment the appropriate filepaths in lines 14-16. Then click ``run``.

Once this has run, adjust the classes by performing the following:

- Choose “No” to “Keep existing available classes”.
- Then select all the remaining classes that are labeled with numbers, right click and ``Select objects by classification`` and then click ``Delete``.
- Finally, highlight the numerical classes again and ``Populate from existing objects`` --> ``All classes``.

Generating Dataset
------------------

1. Go to ``Measure`` --> ``Show Detection Measurements`` --> ``Save``, and save the subsequent file within your project folder as the name of the sample you’re working on (e.g., C83377.txt). This is a csv file where each row represents a cell and all the information about it (including what cell-type class it belongs to).
2. Then go to ``Extensions`` --> ``QuST Analysis Toolbox`` --> ``Export Images for Object Classification``. Create a destination folder in the same directory as your .txt file and with the same name. The parameters should look like the image below. This will export an image for each cell in the sample. This is your training dataset.

Applying a Trained Model
------------------------

To apply your trained model to an image within QuPath, navigate to ``Extensions`` --> ``QuST Analysis Toolbox`` --> ``Object Classification``.

- From the “Model” drop-down menu, select the model you wish to apply. You also have the option to adjust batch size and number of parallel threads. When you are ready, click ``Run``.

Once the model has run, within the Annotation pane, ``Populate from Existing Classes`` again to show the model’s cell-type classifications.

To export the data generated from QuPath and the model, go to ``Measure`` --> ``Show Detection Measurements``.

- Each row in this table is a different cell and information on it. The “Classification” column contains the model‘s cell type classification. Hit ``Save`` to save the resulting table to a text file.

Running DBSCAN-CellX
--------------------

To run DBSCAN-CellX, make sure within the “Annotations” pane to right-click the group of cell-types you are interested in running the algorithm on and select ``Select Objects by Classification``. Then navigate to ``Extensions`` --> ``QuST Analysis Toolbox`` --> ``DBSCAN-CellX`` and run it with your desired parameters. This will run DBSCAN-CellX on the cells you have highlighted. Make sure to specify the name of the DBSCAN-CellX run (e.g., DBSCAN-CellX-LYMPHOCYTES).

To save the results of DBSCAN-CellX in tabular form, go to ``Measure`` --> ``Show Detection Measurements``. You will see new columns appended to the end of the table, which correspond to DBSCAN-CellX's output. Hit ``save`` to save the new file.

To visualize the results of DBSCAN-CellX, go to ``Measure`` --> ``Show Measurement Maps``. Scroll down to the last few measurement types and select “<DBSCAN-CellX-run_name>: Edge Degree”. I prefer to use the color scale “Viridis”. To better see the cells and their distinct color labels, click ``View`` --> ``Cell Display`` --> ``Cell Boundaries Only``, and be sure to have the following button selected.

The result should look something like this:

.. image:: path/to/image.png

In conclusion, we’ve covered how to access QuPath, prep and analyze samples, generate a dataset from them to train a ML model on, install and apply the model within QuPath, and then run DBSCAN-CellX on the results. I hope this tutorial was helpful, feel free to reach out to me (sara.lichtarge@pfizer.com) or Chao-Hui Huang (Chao-Hui.Huang@pfizer.com) with any questions.

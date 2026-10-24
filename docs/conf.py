# Configuration file for the Sphinx documentation builder.
# https://www.sphinx-doc.org/en/master/usage/configuration.html

project = "QuST"
copyright = "Chao-Hui Huang, Sara Lichtarge, Diane Fernandez (2025)"
author = "Chao-Hui Huang, Sara Lichtarge and Diane Fernandez"
release = "0.6.0"

extensions = [
    "sphinx_copybutton",  # copy button on code blocks
]

language = "en"
templates_path = ["_templates"]
exclude_patterns = ["_build", "Thumbs.db", ".DS_Store"]

html_context = {
    "github_user": "huangch",
    "github_repo": "qust",
    "github_version": "main",
    "doc_path": "docs",
}

html_theme = "pydata_sphinx_theme"
html_static_path = ["_static"]
html_theme_options = {
    "logo": {"text": "QuST"},
    "use_edit_page_button": True,
    "show_toc_level": 1,
    "navbar_align": "left",
    "github_url": "https://github.com/huangch/qust",
    "navbar_end": ["theme-switcher", "navbar-icon-links"],
    "header_links_before_dropdown": 6,
}

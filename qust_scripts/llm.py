import os
import requests
import pandas as pd
import scanpy as sc
import numpy as np
import matplotlib.pyplot as plt
from matplotlib import cm
import seaborn as sns
import textwrap
from goatools.base import download_go_basic_obo
from goatools.base import download_ncbi_associations
from goatools.obo_parser import GODag
from goatools.anno.genetogo_reader import Gene2GoReader
from goatools.goea.go_enrichment_ns import GOEnrichmentStudyNS
from genes_ncbi_homo_sapiens_proteincoding import GENEID2NT as GeneID2nt_homo
from openai import OpenAI
import json
import argparse
from dotenv import load_dotenv, dotenv_values 

load_dotenv() 
 
# API info
from config import *

sc.settings.verbosity = 3  # verbosity: errors (0), warnings (1), info (2), hints (3)
sc.logging.print_header()
sc.settings.set_figure_params(dpi=80, facecolor="white")

# #To list config items
# verification_url = verification_url
# mulesoft_url = mulesoft_url
#
# client_id = client_id
# client_secret = client_secret

parser = argparse.ArgumentParser()
parser.add_argument('action', type=str, help='action')
parser.add_argument('data_file', type=str, help='data_file')
parser.add_argument('result_file', type=str, help='result_file')
parser.add_argument('-bp', action='store_true', help="include biological process")
parser.add_argument('-mf', action='store_true', help="include molecular functions")
parser.add_argument('-cc', action='store_true', help="include cellular components")
parser.add_argument('-p', '--prompt', type=str, default="", help='prompt', required=False)
parser.add_argument('-g', '--topg', type=int, default=100, help='topg', required=False)
parser.add_argument('-t', '--topt', type=int, default=10, help='topt', required=False)

opt = parser.parse_args()

# def generate_token():
#     '''
#     Generates the Bearer token needed for OpenAI API authentication. Returns the Bearer token as a string.
#     '''
#     try:
#         url = f"{verification_url}"
#         payload = f"client_id={client_id}&client_secret={client_secret}"
#         headers = {"Content-Type": "application/x-www-form-urlencoded"}
#         response = requests.request("POST", url, headers=headers, data=payload)
#         token = response.json()["access_token"]
#         return "Bearer " + token
#
#     except Exception as e:
#         return {
#             "status": "error",
#             "data": e,
#             "message": e.orig.args if hasattr(e, "orig") else f"{e}",
#         }
#
#
# def generate_completion(payload, mulesoft_url, bearer_token):
#     '''
#     Generates a completion from the MuleSoft endpoint on the Vox platform and returns the result. 
#     Returns the completion as a string.
#
#     Args:
#         payload: (dict) payload for completion
#         mulesoft_url: (str) url endpoint of the MuleSoft connection
#         bearer_token: (str) authentication token generated
#     '''
#     url = mulesoft_url + '/chatCompletion'
#     payload = json.dumps(payload) #needed to convert into string
#     headers = {"Authorization": bearer_token, "Content-Type": "application/json"}
#
#     response = requests.request("POST", url, headers=headers, data=payload)
#
#     if response.status_code == 200:
#         result = response.json()["result"]
#         # token = response.json()["totalTokens"]
#         return result
#     else:
#         print("Unable to call with status code {}".format(response.status_code))
    
# def llm_query(prompt, model):
#
#     try:
#         text_response = ''
#         #Generate the token, valid for 30 min    
#         token = generate_token()
#         assert isinstance(token,str), "Check if client_id and client_secret are passed incorrectly from the config.py file"
#
#         #health check
#         url = mulesoft_url + '/health_check'
#
#         bearer_token = token #generate_token()
#         headers = {"Authorization": bearer_token, "Content-Type": "application/json"}
#         response = requests.request("GET", url, headers=headers)
#         response_status = response.json()['status']
#         print(f"LLM API Status: {response_status}")
#
#         if response_status != 'success':
#             raise Exception("LLM API error!")
#
#         #Detailing models to be used during examples
#         model = 'gpt-4'
#
#         prompt = f"""Identify corresponding gene ontology term IDs from below content, and return the result in json format. 
#
# '''{opt.prompt}'''
#
# """
#
#         #Get the completion with input parameters
#         system_prompt = """You are a computational biologist. You are working on gene ontology related tasks."""
#
#         payload = {
#         "messages": [
#             {
#                 "role": "system",
#                 "content": system_prompt
#             },
#             {
#                 "role": "user",
#                 "content": prompt
#             }
#         ],
#         "engine": model,
#         "max_tokens": "4096",
#         "temperature": 0
#         }
#
#         text_response = eval(generate_completion(payload, mulesoft_url, bearer_token)).get('content')
#
#     except Exception as e:      # works on python 3.x
#         print(repr(e))
#         # result_json_data['success'] = False
#     finally:
#         return text_response
            
def llm_query(prompt, model):
    client = OpenAI()

    completion = client.chat.completions.create(
      model=model,
      messages=[
        {"role": "system", "content": "You are a computational biologist. You are working on gene ontology related tasks."},
        {"role": "user", "content": prompt}
      ]
    )

    return completion.choices[0].message.content


def backward_analysis(opt):
    result_json_data = {"success": False}
    
    try:
        # #Generate the token, valid for 30 min    
        # token = generate_token()
        # assert isinstance(token,str), "Check if client_id and client_secret are passed incorrectly from the config.py file"
        #
        # #health check
        # url = mulesoft_url + '/health_check'
        #
        # bearer_token = token #generate_token()
        # headers = {"Authorization": bearer_token, "Content-Type": "application/json"}
        # response = requests.request("GET", url, headers=headers)
        # response_status = response.json()['status']
        # print(f"LLM API Status: {response_status}")
        #
        # if response_status != 'success':
        #     raise Exception("LLM API error!")
        
        #Detailing models to be used during examples
        model = 'gpt-4'
        
        prompt = f"""Identify corresponding gene ontology term IDs from below content, and return the result in json format. 

'''{opt.prompt}'''

"""

        # #Get the completion with input parameters
        # system_prompt = """You are a computational biologist. You are working on gene ontology related tasks."""
        #
        # payload = {
        # "messages": [
        #     {
        #         "role": "system",
        #         "content": system_prompt
        #     },
        #     {
        #         "role": "user",
        #         "content": prompt
        #     }
        # ],
        # "engine": model,
        # "max_tokens": "4096",
        # "temperature": 0
        # }
        
        # text_response = eval(generate_completion(payload, mulesoft_url, bearer_token)).get('content')
        text_response = llm_query(prompt, model)
        text_response_json = json.loads(text_response)
        go_terms = list(text_response_json.keys())
     
        print('\nLLM interpretation finished\n')
        
        adata = sc.read_csv(opt.data_file)
        
        adata.var_names_make_unique()
        
        sc.pp.filter_cells(adata, min_counts=10)
        sc.pp.filter_genes(adata, min_cells=5)
        
        # adata.var["mt"] = adata.var_names.str.startswith("MT-")
        # sc.pp.calculate_qc_metrics(adata, qc_vars=["mt"], inplace=True)
        
        sc.pp.calculate_qc_metrics(adata, percent_top=(10, 20, 50, 150), inplace=True)
        
        upper_lim = np.quantile(adata.obs.n_genes_by_counts.values, .98)
        lower_lim = np.quantile(adata.obs.n_genes_by_counts.values, .02)
        adata = adata[(adata.obs.n_genes_by_counts < upper_lim) & (adata.obs.n_genes_by_counts > lower_lim)]
        
        # adata.layers["counts"] = adata.X.copy()
        
        sc.pp.normalize_total(adata, inplace=True)
                
        print('\nSingle cell analysis finished\n')

        # obo_fname = download_go_basic_obo()
        fin_gene2go = download_ncbi_associations()
        # obodag = GODag("go-basic.obo")
        
        #run one time to initialize
        mapper = {}
        
        for key in GeneID2nt_homo:
            mapper[GeneID2nt_homo[key].Symbol] = GeneID2nt_homo[key].GeneID
            
        inv_map = {v: k for k, v in mapper.items()}
        
        #run one time to initialize

        # Read NCBI's gene2go. Store annotations in a list of namedtuples
        objanno = Gene2GoReader(fin_gene2go, taxids=[9606])
        # Get namespace2association where:
        #    namespace is:
        #        BP: biological_process               
        #        MF: molecular_function
        #        CC: cellular_component
        #    assocation is a dict:
        #        key: NCBI GeneID
        #        value: A set of GO IDs associated with that gene
        ns2assoc = objanno.get_ns2assc()
              
        print('\nGene Ontoloty initialized\n')
        
        goi = {}
        
        if opt.bp:
            for gene in ns2assoc['BP']:
                if gene in list(inv_map.keys()):
                    gene_label = inv_map[gene]
                    if gene_label in adata.var.index.tolist():  
                        for term in go_terms:
                            if term in list(ns2assoc['BP'][gene]):
                                if gene_label in list(goi.keys()):
                                    goi[gene_label] += 1
                                else:
                                    goi[gene_label] = 1
                                
        if opt.mf:
            for gene in ns2assoc['MF']:
                if gene in list(inv_map.keys()):
                    gene_label = inv_map[gene]
                    if gene_label in adata.var.index.tolist():  
                        for term in go_terms:
                            if term in list(ns2assoc['MF'][gene]):
                                if gene_label in list(goi.keys()):
                                    goi[gene_label] += 1
                                else:
                                    goi[gene_label] = 1                    
                
        if opt.cc:
            for gene in ns2assoc['CC']:
                if gene in list(inv_map.keys()):
                    gene_label = inv_map[gene]
                    if gene_label in adata.var.index.tolist():  
                        for term in go_terms:
                            if term in list(ns2assoc['CC'][gene]):
                                if gene_label in list(goi.keys()):
                                    goi[gene_label] += 1
                                else:
                                    goi[gene_label] = 1         
                                    
        print('\nGO term evaluation finished\n')
        
        index_df = adata.to_df().apply(lambda x: x.argsort().argsort(), axis=1)
        result_df = index_df.apply(lambda s: s.apply(lambda v: (goi[s.name] if s.name in list(goi.keys()) else 0) if v > (len(adata.var.index)-opt.topg-1) else 0), axis=0).sum(axis=1)
        result_df = (result_df-result_df.min())/(result_df-result_df.min()).sum()
        result_dict = result_df.to_dict()
        
        print('\nGO term weight computed\n')
        
        result_json_data['results'] = result_dict
        result_json_data['success'] = True
        
    except Exception as e:      # works on python 3.x
        print(repr(e))
        # result_json_data['success'] = False
    finally:
        with open(opt.result_file, "w") as fp:
            json.dump(result_json_data , fp)     
            
            
def forward_analysis_high_gene_expression(opt):
    result_json_data = {"success": False}
    
    try:
        # #Generate the token, valid for 30 min    
        # token = generate_token()
        # assert isinstance(token,str), "Check if client_id and client_secret are passed incorrectly from the config.py file"
        #
        # #health check
        # url = mulesoft_url + '/health_check'
        #
        # bearer_token = token #generate_token()
        # headers = {"Authorization": bearer_token, "Content-Type": "application/json"}
        # response = requests.request("GET", url, headers=headers)
        # response_status = response.json()['status']
        # print(f"LLM API Status: {response_status}")
        #
        # if response_status != 'success':
        #     raise Exception("LLM API error!")
    
        adata = sc.read_csv(opt.data_file)
        
        adata.var_names_make_unique()
        
        sc.pp.filter_cells(adata, min_counts=10)
        sc.pp.filter_genes(adata, min_cells=5)
        
        # adata.var["mt"] = adata.var_names.str.startswith("MT-")
        # sc.pp.calculate_qc_metrics(adata, qc_vars=["mt"], inplace=True)
        
        sc.pp.calculate_qc_metrics(adata, percent_top=(10, 20, 50, 150), inplace=True)
        
        upper_lim = np.quantile(adata.obs.n_genes_by_counts.values, .98)
        lower_lim = np.quantile(adata.obs.n_genes_by_counts.values, .02)
        adata = adata[(adata.obs.n_genes_by_counts < upper_lim) & (adata.obs.n_genes_by_counts > lower_lim)]
        
        # adata.layers["counts"] = adata.X.copy()
        
        sc.pp.normalize_total(adata, inplace=True)
        sc.pp.log1p(adata)
        # sc.pp.highly_variable_genes(adata, min_mean=0.0125, max_mean=3, min_disp=0.5) 
        sc.pp.highly_variable_genes(adata) 
        
        top_gene_df = pd.DataFrame(adata.var.nlargest(opt.topg, 'means')['means'])
        top_genes =  top_gene_df.index.tolist()
        
        # obo_fname = download_go_basic_obo()
        fin_gene2go = download_ncbi_associations()
        obodag = GODag("go-basic.obo")
        
        #run one time to initialize
        mapper = {}
        
        for key in GeneID2nt_homo:
            mapper[GeneID2nt_homo[key].Symbol] = GeneID2nt_homo[key].GeneID
            
        inv_map = {v: k for k, v in mapper.items()}
        
        #run one time to initialize

        # Read NCBI's gene2go. Store annotations in a list of namedtuples
        objanno = Gene2GoReader(fin_gene2go, taxids=[9606])
        # Get namespace2association where:
        #    namespace is:
        #        BP: biological_process               
        #        MF: molecular_function
        #        CC: cellular_component
        #    assocation is a dict:
        #        key: NCBI GeneID
        #        value: A set of GO IDs associated with that gene
        # ns2assoc = objanno.get_ns2assc()
        
        ns2assoc_base = objanno.get_ns2assc()
        ns2assoc = {}
        
        if opt.bp:
            ns2assoc['BP'] = ns2assoc_base['BP']
            
        if opt.cc:    
            ns2assoc['CC'] = ns2assoc_base['CC']
        
        if opt.mf:
            ns2assoc['MF'] = ns2assoc_base['MF']
            
        
        #run one time to initialize
        goeaobj = GOEnrichmentStudyNS(
                GeneID2nt_homo.keys(), # List of mouse protein-coding genes
                ns2assoc, # geneid/GO associations
                obodag, # Ontologies
                propagate_counts = False,
                alpha = 0.05, # default significance cut-off
                methods = ['fdr_bh']) # defult multipletest correction method        
        
        
        #run one time to initialize
        GO_items = []
        
        if opt.bp:
            temp = goeaobj.ns2objgoea['BP'].assoc
            for item in temp:
                GO_items += temp[item]
            
        if opt.cc:
            temp = goeaobj.ns2objgoea['CC'].assoc
            for item in temp:
                GO_items += temp[item]
           
        if opt.mf:
            temp = goeaobj.ns2objgoea['MF'].assoc
            for item in temp:
                GO_items += temp[item]
            
        #pass list of gene symbols
        def go_it(test_genes):
            print(f'input genes: {len(test_genes)}')
            
            mapped_genes = []
            for gene in test_genes:
                try:
                    mapped_genes.append(mapper[gene])
                except:
                    pass
            print(f'mapped genes: {len(mapped_genes)}')
            
            goea_results_all = goeaobj.run_study(mapped_genes)
            goea_results_sig = [r for r in goea_results_all if r.p_fdr_bh < 0.05]
            GO = pd.DataFrame(list(map(lambda x: [x.GO, x.goterm.name, x.goterm.namespace, x.p_uncorrected, x.p_fdr_bh,\
                           x.ratio_in_study[0], x.ratio_in_study[1], GO_items.count(x.GO), list(map(lambda y: inv_map[y], x.study_items)),\
                           ], goea_results_sig)), columns = ['GO', 'term', 'class', 'p', 'p_corr', 'n_genes',\
                                                            'n_study', 'n_go', 'study_genes'])
        
            GO = GO[GO.n_genes > 1]
            GO['per'] = GO.n_genes/GO.n_go
            
            return GO

        # df = go_it(ec_markers.Gene.values)
        df = go_it(top_genes)
        df = df[0:opt.topt]
        
        if df.shape[0] == 0:
            raise Exception("GOEA error!")
        
        #Detailing models to be used during examples
        model = 'gpt-4'

        text = ', '.join(df['term'].to_list())
        
        prompt = f"""Write a paragraph of an integrative summarization for the below given terms.
The paragraph is comprehensive, focus on tissue biological structure and clinical perspectives.

'''{text}'''

"""

        # #Get the completion with input parameters
        # system_prompt = """You are a computational biologist. You are writing an summarization based on the given biological terms."""
        #
        # payload = {
        # "messages": [
        #     {
        #         "role": "system",
        #         "content": system_prompt
        #     },
        #     {
        #         "role": "user",
        #         "content": prompt
        #     }
        # ],
        # "engine": model,
        # "max_tokens": "4096",
        # "temperature": 0
        # }
        #
        # text_response = eval(generate_completion(payload, mulesoft_url, bearer_token)).get('content')
        text_response = llm_query(prompt, model)
        
        print('\n')
        print(text)
        print('\n')
        print(text_response)
        print('\n')
                
        fig = plt.figure(figsize = (6,6))
        norm = plt.Normalize(vmin = df.p_corr.min(), vmax = df.p_corr.max())
        mapper = cm.ScalarMappable(norm = norm)
        ax = sns.barplot(data = df, x = 'per', y = 'term', palette = mapper.to_rgba(df.p_corr.values))
        ax.set_yticklabels([textwrap.fill(e, 40) for e in df['term']])
        ax.figure.colorbar(mapper, ax=ax)
        
        fig.tight_layout()
        # plt.show()
        fig.savefig(opt.result_file+".png", bbox_inches='tight')
        plt.close(fig)  
        
        result_json_data['text_response'] = text_response
        result_json_data['success'] = True
        
    except Exception as e:      # works on python 3.x
        print(repr(e))
        # result_json_data['success'] = False
    finally:
        with open(opt.result_file, "w") as fp:
            json.dump(result_json_data , fp)        
        

def forward_analysis_differential_gene_expression(opt):
    result_json_data = {"success": False}
    
    try:
        if not opt.bp and not opt.mf and not opt.cc:
            raise Exception ("all BP MF CC are missed") 
        
        # #Generate the token, valid for 30 min    
        # token = generate_token()
        # assert isinstance(token,str), "Check if client_id and client_secret are passed incorrectly from the config.py file"
        #
        # #health check
        # url = mulesoft_url + '/health_check'
        #
        # bearer_token = token #generate_token()
        # headers = {"Authorization": bearer_token, "Content-Type": "application/json"}
        # response = requests.request("GET", url, headers=headers)
        # response_status = response.json()['status']
        # print(f"LLM API Status: {response_status}")
        #
        # if response_status != 'success':
        #     raise Exception("LLM API error!")
    
        adata = sc.read_csv(opt.data_file)
        
        adata.var_names_make_unique()
        
        sc.pp.filter_cells(adata, min_counts=10)
        sc.pp.filter_genes(adata, min_cells=5)
        
        # adata.var["mt"] = adata.var_names.str.startswith("MT-")
        # sc.pp.calculate_qc_metrics(adata, qc_vars=["mt"], inplace=True)
        
        sc.pp.calculate_qc_metrics(adata, percent_top=(10, 20, 50, 150), inplace=True)
        
        upper_lim = np.quantile(adata.obs.n_genes_by_counts.values, .98)
        lower_lim = np.quantile(adata.obs.n_genes_by_counts.values, .02)
        adata = adata[(adata.obs.n_genes_by_counts < upper_lim) & (adata.obs.n_genes_by_counts > lower_lim)]
        
        # adata.layers["counts"] = adata.X.copy()
        
        sc.pp.normalize_total(adata, inplace=True)
        sc.pp.log1p(adata)
        # sc.pp.highly_variable_genes(adata, min_mean=0.0125, max_mean=3, min_disp=0.5) 
        sc.pp.highly_variable_genes(adata) 
        adata = adata[:, adata.var.highly_variable]
        # sc.pp.scale(adata, max_value=10)
        sc.pp.pca(adata)
        sc.pp.neighbors(adata)
        sc.tl.umap(adata)
        sc.tl.leiden(adata)
        sc.tl.rank_genes_groups(adata, 'leiden', method='wilcoxon')
                
        #find markers
        results = adata.uns['rank_genes_groups']
        out = np.empty(shape=(0, 5), dtype=np.float64)
        for group in results['names'].dtype.names:
            out = np.vstack((out, np.vstack((results['names'][group],
                                             results['scores'][group],
                                             results['pvals_adj'][group],
                                             results['logfoldchanges'][group],
                                             np.array([group] * len(results['names'][group])).astype('object'))).T))
        adata.uns['markers'] = pd.DataFrame(out, columns = ['Gene', 'scores', 'pval_adj', 'lfc', 'cluster'])
 
        fin_gene2go = download_ncbi_associations()
        obodag = GODag("go-basic.obo")
        
        #run one time to initialize
        mapper = {}
        
        for key in GeneID2nt_homo:
            mapper[GeneID2nt_homo[key].Symbol] = GeneID2nt_homo[key].GeneID
            
        inv_map = {v: k for k, v in mapper.items()}
        
        #run one time to initialize

        # Read NCBI's gene2go. Store annotations in a list of namedtuples
        objanno = Gene2GoReader(fin_gene2go, taxids=[9606])
        # Get namespace2association where:
        #    namespace is:
        #        BP: biological_process               
        #        MF: molecular_function
        #        CC: cellular_component
        #    assocation is a dict:
        #        key: NCBI GeneID
        #        value: A set of GO IDs associated with that gene
        ns2assoc_base = objanno.get_ns2assc()
        ns2assoc = {}
        
        if opt.bp:
            ns2assoc['BP'] = ns2assoc_base['BP']
            
        if opt.cc:    
            ns2assoc['CC'] = ns2assoc_base['CC']
        
        if opt.mf:
            ns2assoc['MF'] = ns2assoc_base['MF']
        
        #run one time to initialize
        goeaobj = GOEnrichmentStudyNS(
                GeneID2nt_homo.keys(), # List of mouse protein-coding genes
                ns2assoc, # geneid/GO associations
                obodag, # Ontologies
                propagate_counts = False,
                alpha = 0.05, # default significance cut-off
                methods = ['fdr_bh']) # defult multipletest correction method        
        
        
        #run one time to initialize
        GO_items = []
        
        if opt.bp:
            temp = goeaobj.ns2objgoea['BP'].assoc
            for item in temp:
                GO_items += temp[item]
            
        if opt.cc:
            temp = goeaobj.ns2objgoea['CC'].assoc
            for item in temp:
                GO_items += temp[item]
           
        if opt.mf:
            temp = goeaobj.ns2objgoea['MF'].assoc
            for item in temp:
                GO_items += temp[item]
            
        #pass list of gene symbols
        def go_it(test_genes):
            print(f'input genes: {len(test_genes)}')
            
            mapped_genes = []
            for gene in test_genes:
                try:
                    mapped_genes.append(mapper[gene])
                except:
                    pass
            print(f'mapped genes: {len(mapped_genes)}')
            
            goea_results_all = goeaobj.run_study(mapped_genes)
            goea_results_sig = [r for r in goea_results_all if r.p_fdr_bh < 0.05]
            GO = pd.DataFrame(list(map(lambda x: [x.GO, x.goterm.name, x.goterm.namespace, x.p_uncorrected, x.p_fdr_bh,\
                           x.ratio_in_study[0], x.ratio_in_study[1], GO_items.count(x.GO), list(map(lambda y: inv_map[y], x.study_items)),\
                           ], goea_results_sig)), columns = ['GO', 'term', 'class', 'p', 'p_corr', 'n_genes',\
                                                            'n_study', 'n_go', 'study_genes'])
        
            GO = GO[GO.n_genes > 1]
            GO['per'] = GO.n_genes/GO.n_go
        
            return GO

        # test_genes = []
        markers = adata.uns['markers']
        text_list = []
        GO_list = []
        for cluster in markers.cluster.unique().tolist():
            cluster_markers = markers[(markers.cluster == cluster) & (markers.pval_adj < 0.05) & (markers.lfc > 1.5)]
            cluster_key_genes = cluster_markers.Gene.values[:opt.topg].tolist()
            
            GO = go_it(cluster_key_genes)
            
            if len(GO) > 0:
                GO = GO[0:opt.topt]
                GO_list.append(GO)
                go_term_list = GO['term'].to_list()
                text = ', '.join(go_term_list)
                text_list.append(text)
            
            
            # if GO.shape[0] == 0:
            # raise Exception("GOEA error!")
        
        if len(GO_list) == 0:
            raise Exception("Failed")
        
        GO = pd.concat(GO_list)
        GO_curated = pd.DataFrame(columns=GO.columns)
        for g in GO.GO.unique():
            GO_curated.loc[len(GO_curated)] = GO[(GO.GO==g) & (GO['p']==GO[GO.GO==g]['p'].min())].iloc[0].tolist()
                    
        GO_curated.sort_values(by='p_corr', inplace=True) 
        
        #Detailing models to be used during examples
        model = 'gpt-4'

        text = '\n\n'.join(text_list)
        
        prompt = f"""Write a paragraph of a summary for the given content.
In the content, each row represent some biological processes, cellular components and/or molecular functions of a certain cell group.
The summary focuses on the interaction (if any) among these groups.
The paragraph is integrative and comprehensive, focus on tissue biological structure and clinical perspectives.

'''{text}'''

"""

        # #Get the completion with input parameters
        # system_prompt = """You are a computational biologist. You are writing an summarization based on the given biological terms."""
        #
        # payload = {
        # "messages": [
        #     {
        #         "role": "system",
        #         "content": system_prompt
        #     },
        #     {
        #         "role": "user",
        #         "content": prompt
        #     }
        # ],
        # "engine": model,
        # "max_tokens": "4096",
        # "temperature": 0
        # }
        #
        # text_response = eval(generate_completion(payload, mulesoft_url, bearer_token)).get('content')
        
        text_response = llm_query(prompt, model)
        
        print('\n')
        print(text)
        print('\n')
        print(text_response)
        print('\n')
                
        fig = plt.figure(figsize = (9,9))
        norm = plt.Normalize(vmin = GO_curated.p_corr.min(), vmax = GO_curated.p_corr.max())
        mapper = cm.ScalarMappable(norm = norm)
        ax = sns.barplot(data = GO_curated, x = 'per', y = 'term', palette = mapper.to_rgba(GO_curated.p_corr.values))
        ax.set_yticklabels([textwrap.fill(e, 40) for e in GO_curated['term']])
        ax.figure.colorbar(mapper, ax=ax)
        
        fig.tight_layout()
        # plt.show()
        fig.savefig(opt.result_file+".png", bbox_inches='tight')
        plt.close(fig)  
        
        result_json_data['text_response'] = text_response
        result_json_data['success'] = True
        
    except Exception as e:      # works on python 3.x
        print(repr(e))
        # result_json_data['success'] = False
    finally:
        with open(opt.result_file, "w") as fp:
            json.dump(result_json_data , fp)  
            
        
def forward_analysis_comparative_gene_expression(opt):
    result_json_data = {"success": False}
    
    # #Generate the token, valid for 30 min    
    # token = generate_token()
    # assert isinstance(token,str), "Check if client_id and client_secret are passed incorrectly from the config.py file"
    #
    # #health check
    # url = mulesoft_url + '/health_check'
    #
    # bearer_token = token #generate_token()
    # headers = {"Authorization": bearer_token, "Content-Type": "application/json"}
    # response = requests.request("GET", url, headers=headers)
    # print(response)
    
    try:
        df = pd.read_csv(opt.data_file)
        adata = sc.AnnData(df.iloc[0:, 2:])
        label = df.loc[[int(i) for i in adata.obs.index], 'label']
        adata.obs.loc[:,'label']=np.array([str(i) for i in label])
        adata.obs.loc[:,'label'] = adata.obs.loc[:,'label'].astype('category')




        adata.var_names_make_unique()
        
        sc.pp.filter_cells(adata, min_counts=10)
        sc.pp.filter_genes(adata, min_cells=5)
        
        # adata.var["mt"] = adata.var_names.str.startswith("MT-")
        # sc.pp.calculate_qc_metrics(adata, qc_vars=["mt"], inplace=True)
        
        sc.pp.calculate_qc_metrics(adata, percent_top=(10, 20, 50, 150), inplace=True)
        
        upper_lim = np.quantile(adata.obs.n_genes_by_counts.values, .98)
        lower_lim = np.quantile(adata.obs.n_genes_by_counts.values, .02)
        adata = adata[(adata.obs.n_genes_by_counts < upper_lim) & (adata.obs.n_genes_by_counts > lower_lim)]
        
        # adata.layers["counts"] = adata.X.copy()
        
        sc.pp.normalize_total(adata, inplace=True)
        sc.pp.log1p(adata)
        # sc.pp.highly_variable_genes(adata, min_mean=0.0125, max_mean=3, min_disp=0.5) 
        sc.pp.highly_variable_genes(adata) 
        adata = adata[:, adata.var.highly_variable]




        
        sc.tl.rank_genes_groups(adata, 'label', method='wilcoxon')
        rank_genes_df = sc.get.rank_genes_groups_df(adata, group="1")
        top_genes = rank_genes_df['names'][:opt.topg].tolist()

        # obo_fname = download_go_basic_obo()
        fin_gene2go = download_ncbi_associations()
        obodag = GODag("go-basic.obo")
        
        #run one time to initialize
        mapper = {}
        
        for key in GeneID2nt_homo:
            mapper[GeneID2nt_homo[key].Symbol] = GeneID2nt_homo[key].GeneID
            
        inv_map = {v: k for k, v in mapper.items()}
        
        #run one time to initialize

        # Read NCBI's gene2go. Store annotations in a list of namedtuples
        objanno = Gene2GoReader(fin_gene2go, taxids=[9606])
        # Get namespace2association where:
        #    namespace is:
        #        BP: biological_process               
        #        MF: molecular_function
        #        CC: cellular_component
        #    assocation is a dict:
        #        key: NCBI GeneID
        #        value: A set of GO IDs associated with that gene
        ns2assoc_base = objanno.get_ns2assc()
        ns2assoc = {}
        
        if opt.bp:
            ns2assoc['BP'] = ns2assoc_base['BP']
            
        if opt.cc:    
            ns2assoc['CC'] = ns2assoc_base['CC']
        
        if opt.mf:
            ns2assoc['MF'] = ns2assoc_base['MF']
        
        #run one time to initialize
        goeaobj = GOEnrichmentStudyNS(
                GeneID2nt_homo.keys(), # List of mouse protein-coding genes
                ns2assoc, # geneid/GO associations
                obodag, # Ontologies
                propagate_counts = False,
                alpha = 0.05, # default significance cut-off
                methods = ['fdr_bh']) # defult multipletest correction method        
        
        #run one time to initialize
        GO_items = []
        
        if opt.bp:
            temp = goeaobj.ns2objgoea['BP'].assoc
            for item in temp:
                GO_items += temp[item]
            
        if opt.cc:
            temp = goeaobj.ns2objgoea['CC'].assoc
            for item in temp:
                GO_items += temp[item]
           
        if opt.mf:
            temp = goeaobj.ns2objgoea['MF'].assoc
            for item in temp:
                GO_items += temp[item]
            
        #pass list of gene symbols
        def go_it(test_genes):
            print(f'input genes: {len(test_genes)}')
            
            mapped_genes = []
            for gene in test_genes:
                try:
                    mapped_genes.append(mapper[gene])
                except:
                    pass
            print(f'mapped genes: {len(mapped_genes)}')
            
            goea_results_all = goeaobj.run_study(mapped_genes)
            goea_results_sig = [r for r in goea_results_all if r.p_fdr_bh < 0.05]
            GO = pd.DataFrame(list(map(lambda x: [x.GO, x.goterm.name, x.goterm.namespace, x.p_uncorrected, x.p_fdr_bh,\
                           x.ratio_in_study[0], x.ratio_in_study[1], GO_items.count(x.GO), list(map(lambda y: inv_map[y], x.study_items)),\
                           ], goea_results_sig)), columns = ['GO', 'term', 'class', 'p', 'p_corr', 'n_genes',\
                                                            'n_study', 'n_go', 'study_genes'])
        
            GO = GO[GO.n_genes > 1]
            GO['per'] = GO.n_genes/GO.n_go
            
            return GO

        df = go_it(top_genes)
        df = df[0:opt.topt]
        
        if df.shape[0] == 0:
            raise Exception("GOEA error!")
        
        #Detailing models to be used during examples
        model = 'gpt-4'

        text = ', '.join(df['term'].to_list())
        
        prompt = f"""Write a paragraph of an integrative summarization for the below given terms.
The paragraph must be comprehensive, focus on tissue biological structure and clinical perspectives without involving general concepts.

'''{text}'''

"""

        # #Get the completion with input parameters
        # system_prompt = """You are a computational biologist. You are writing an summarization based on the given biological terms."""
        #
        # payload = {
        # "messages": [
        #     {
        #         "role": "system",
        #         "content": system_prompt
        #     },
        #     {
        #         "role": "user",
        #         "content": prompt
        #     }
        # ],
        # "engine": model,
        # "max_tokens": "4096",
        # "temperature": 0
        # }
        #
        # text_response = eval(generate_completion(payload, mulesoft_url, bearer_token)).get('content')
        
        text_response = llm_query(prompt, model)
        
        print('\n')
        print(text)
        print('\n')
        print(text_response)
        print('\n')
                
        fig = plt.figure(figsize = (6,6))
        norm = plt.Normalize(vmin = df.p_corr.min(), vmax = df.p_corr.max())
        mapper = cm.ScalarMappable(norm = norm)
        ax = sns.barplot(data = df, x = 'per', y = 'term', palette = mapper.to_rgba(df.p_corr.values))
        ax.set_yticklabels([textwrap.fill(e, 40) for e in df['term']])
        ax.figure.colorbar(mapper, ax=ax)
        
        fig.tight_layout()
        # plt.show()
        fig.savefig(opt.result_file+".png", bbox_inches='tight')
        plt.close(fig)  
        
        result_json_data['text_response'] = text_response
        result_json_data['success'] = True
        
    except Exception as e:      
        print(repr(e))
        
    finally:
        with open(opt.result_file, "w") as fp:
            json.dump(result_json_data , fp)          
           
if __name__ == '__main__':
    if opt.action == 'hkgh':
        forward_analysis_high_gene_expression(opt)
    elif opt.action == 'hkgd':
        forward_analysis_differential_gene_expression(opt)
    elif opt.action == 'ckg':
        forward_analysis_comparative_gene_expression(opt)
    elif opt.action == 'query':
        backward_analysis(opt)
    

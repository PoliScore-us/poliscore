# PoliScore
*A Framework and Benchmark Suite for Policy Quality Engineering*


PoliScore is a 🚨**WORK IN PROGRESS**🚨. Much of the published website was NOT generated with these principles in mind (yet). What you're about to read is more of  a roadmap than a description of how the current website works.


PoliScore is a first-principles framework for evaluating the structural quality of public policy, together with **PoliBench**, a benchmark suite for assessing whether AI systems can reason reliably about legislation.

The core idea is to treat policy evaluation as an engineering discipline: **policy quality engineering**. Instead of asking whether a policy aligns with a particular ideology, PoliScore asks whether a piece of legislation is well-designed, feasible, fair, and institutionally sound according to transparent, non-partisan criteria.

---

## ✳️ Overview

PoliScore evaluates legislation along seven core dimensions of policy quality:

1. **Problem Clarity \& Causal Validity**  
   Does the bill clearly define the problem and target the relevant causal mechanisms?

2. **Evidence Base \& Empirical Support**  
   Is the intervention grounded in empirical research, historical precedent, or credible comparative data?

3. **Implementation Feasibility**  
   Can existing institutions realistically execute the mandates with available resources, logistics, and time?

4. **Economic Efficiency \& Fiscal Sustainability**  
   Are resources used responsibly, with sustainable funding and acceptable economic distortions?

5. **Distributional Impact \& Fairness**  
   How are benefits and burdens distributed across groups, and are any imbalances defensible?

6. **Governance Integrity \& Institutional Risk**  
   Does the policy maintain transparency, accountability, and resilience while minimizing opportunities for abuse?

7. **Unintended Consequences \& Systemic Risk**  
   Does the bill introduce fragility, perverse incentives, or cascading systemic risks?

These dimensions are derived from work in political philosophy, welfare economics, institutional theory, governance studies, and systems thinking.

---

## 📊 PoliBench: Benchmarking AI Policy Reasoning

**PoliBench** operationalizes the PoliScore framework into a set of evaluation tasks for AI models. It focuses on whether a model can:

- Identify unclear or mis-specified policy problems  
- Detect infeasible mandates and administrative overload  
- Recognize governance risks (e.g., concentrated power, weak oversight)  
- Reason about distributional effects and burden shifting  
- Anticipate unintended consequences and system-level fragility  

PoliBench is:

- **Model-agnostic** – any LLM or policy analysis system can be evaluated against it  
- **Dimension-aligned** – tasks are grouped by the seven PoliScore pillars  
- **Reproducible** – designed so that results can be compared across models and over time  

---

## 📄 Whitepaper

The full theoretical and methodological description of PoliScore and PoliBench is available in the whitepaper.

- PDF: <https://raw.githubusercontent.com/PoliScore-us/PoliBench/main/whitepaper.pdf>

The paper includes:

- A first-principles justification for the seven dimensions  
- Formal definitions of each pillar  
- The design of PoliBench and example tasks  
- The scoring and aggregation methodology  
- Comparisons with existing institutions (CBO, think tanks, academic policy analysis, etc.)  
- Limitations, risks, and ethical considerations  
- Discussion of web-search integration and evidence retrieval  

---

## 🔍 Use Cases

PoliScore and PoliBench are intended for:

- **Researchers** – studying AI’s ability to reason about law, policy, and governance  
- **Model developers** – stress-testing advanced language models on policy-specific reasoning  
- **Policy analysts and think tanks** – adding structured, non-partisan diagnostics to existing workflows  
- **Civic technology projects** – helping voters and journalists understand the structural quality of legislation  
- **Educators** – teaching policy design, institutional analysis, and AI evaluation  

---

## 🧪 Example Workflow (Conceptual)

A typical PoliScore-based workflow might look like:

1. Ingest a bill or legislative proposal.  
2. Segment it into sections, mandates, definitions, and appropriations.  
3. For each PoliScore dimension, run a structured analysis prompt or evaluation module.  
4. Generate per-dimension scores (0–100) plus textual explanations.  
5. Aggregate into a composite PoliScore, with clear caveats and limitations.  
6. Provide a human-readable report highlighting strengths, weaknesses, and cross-dimension interactions.

PoliBench can then be used to benchmark and calibrate the AI models used in steps 3–5.

---

## 📚 Citation

If you use PoliScore or PoliBench in academic work, please cite the project. A simple placeholder BibTeX entry (update details as appropriate):

    @misc{rowlands_poliscrore_2025,
      title        = {PoliScore: A Framework and Benchmark Suite for Policy Quality Engineering},
      author       = {Richard Rowlands},
      year         = {2025},
      note         = {Working paper},
      howpublished = {\url{<INSERT PROJECT OR PAPER URL HERE>}}
    }

---

## 🤝 Contributing and Collaboration

PoliScore is intended as a living framework. Useful forms of collaboration include:

- Proposing refinements to the seven dimensions or their operational definitions  
- Contributing additional benchmark tasks or evaluation scenarios  
- Applying PoliScore to real-world legislation and publishing case studies  
- Exploring domain-specific extensions (e.g., health policy, tax policy, climate policy)  
- Investigating bias, robustness, and model behavior on PoliBench tasks  

If you are interested in collaborating, open an issue or reach out to the author.

---

## 🛡️ Disclaimer

PoliScore and PoliBench are tools for **structured analysis**, not for replacing democratic deliberation, expert judgment, or public debate. All evaluations should be interpreted as informative inputs, not as final or authoritative judgments about the merits of any particular policy or legislator.

---

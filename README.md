# PoliScore
*A Framework and Benchmark Suite for Policy Quality Engineering*


PoliScore is a 🚨**WORK IN PROGRESS**🚨


PoliScore is a first-principles framework for evaluating the structural quality of public policy, together with **PoliBench**, a benchmark suite for assessing whether AI systems can reason reliably about legislation.

The core idea is to treat policy evaluation as an engineering discipline: **policy quality engineering**. Instead of asking whether a policy aligns with a particular ideology, PoliScore asks whether a piece of legislation is well-designed, feasible, fair, and institutionally sound according to transparent, non-partisan criteria.

---

## ✳️ Overview

PoliScore evaluates legislation along seven core dimensions of policy quality:

1. **Precision**  
   Does the policy accurately diagnose the underlying issue and target the relevant causal mechanisms?

2. **Evidence**  
   Is the proposed intervention supported by empirical research, historical precedent, or meaningful comparative data?

3. **Feasibility**  
   Can existing institutions realistically execute the policy given resource, logistical, administrative, and temporal constraints? 

4. **Budget**  
   Does the policy use resources responsibly, minimize waste, and avoid unsustainable long-term obligations? 

5. **Fairness**  
   How are benefits and burdens distributed across populations, and does the policy unjustifiably disadvantage certain groups? 

6. **Governance**  
   Does the policy maintain transparency, accountability, and resilience while minimizing opportunities for corruption or abuse? 

7. **Risk**  
   Does the policy introduce fragility, perverse incentives, or cascading failures that undermine the intended outcomes? 

These dimensions are derived from work in political philosophy, welfare economics, institutional theory, governance studies, and systems thinking.

---

## 📄 Whitepaper

The full theoretical and methodological description of PoliScore and PoliBench is available in the whitepaper.

- PDF: <https://raw.githubusercontent.com/poliscore-us/poliscore/main/doc/whitepaper.pdf>

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

## 🛠️ Running DatabaseBuilder

`DatabaseBuilder` is the Quarkus CLI entrypoint that refreshes datasets, generates interpretations, imports OpenAI responses, and syncs downstream storage.

From the `poliscore` directory, you can run it in Quarkus dev mode with:

```bash
mvn -f databuilder/pom.xml quarkus:dev -Dquarkus.args="--help"
```

And then with actual options, for example:

```bash
mvn -f databuilder/pom.xml quarkus:dev -Dquarkus.args="--no-agentic-web-search --reinterpret-parties"
```

If you want to package first and run the built app directly:

```bash
mvn -f databuilder/pom.xml -DskipTests package
java -jar databuilder/target/quarkus-app/quarkus-run.jar --help
```

Current CLI flags:

- `--interpret-press-bills` / `--no-interpret-press-bills`
- `--interpret-new-bills` / `--no-interpret-new-bills`
- `--reinterpret-legislators` / `--no-reinterpret-legislators`
- `--reinterpret-parties` / `--no-reinterpret-parties`
- `--flex-requests` / `--no-flex-requests`
- `--agentic-web-search` / `--no-agentic-web-search`

Running without flags uses the current built-in defaults.

---

## 📚 Citation

If you use PoliScore or PoliBench in academic work, please cite the project. A simple placeholder BibTeX entry (update details as appropriate):

    @misc{poliscrore_2025,
      title        = {PoliScore: A Framework and Benchmark Suite for Policy Quality Engineering},
      author       = {PoliScore},
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

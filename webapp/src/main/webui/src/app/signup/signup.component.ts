import { AfterViewInit, Component, ElementRef, Inject, inject, Input, OnInit, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';
import { Meta, Title } from '@angular/platform-browser';
import { ConfigService } from '../config.service';
import { PurchaseFlowService } from '../billing/purchase-flow.service';
import { environment } from '../../environments/environment';

type Feature = {
  title: string;
  teaser: string;   // short line under the title
  details: string;  // expanded content
  completed?: boolean;
  soon?: boolean;
  eventually?: boolean;
};

/**
 * PoliScoreSignupComponent
 *
 * Usage:
 * <poliscore-signup
 *   [headline]="'Unlock full bill intelligence'"
 *   [onSignup]="() => authService.authorize()"
 *   [onLogin]="() => authService.authorize()"
 *   [loginRouterLink]="'/login'">
 * </poliscore-signup>
 *
 * - If you pass onSignup/onLogin, those callbacks run.
 * - Otherwise, it will navigate to signupRouterLink/loginRouterLink.
 */
@Component({
  selector: 'app-signup',
  standalone: true,
  imports: [CommonModule, RouterModule],
  templateUrl: './signup.component.html',
  styleUrl: './signup.component.scss'
})
export class SignupComponent implements OnInit {
  @Input() headline = 'Unlock Full Access';
  @Input() headlineDescriptor = 'Join now to unlock expert agentic AI analysis for bills and legislators.';

  @Input() signupRouterLink: string | any[] = '/signup';
  @Input() loginRouterLink: string | any[] = '/login';
  @Input() termsRouterLink: string | any[] = '/terms';
  @Input() privacyRouterLink: string | any[] = '/privacy';

  auth = inject(AuthService);
  private flow = inject(PurchaseFlowService);

  // You can tweak wording here any time.
  features: Feature[] = [
    {
      title: 'Bill Expert Analysis',
      teaser: 'Full agentic analysis with embedded source references.',
      details: 'See structured, multi-step reasoning that ties claims to citations. We identify arguments, extract key claims, and attach links back to primary sources for traceability.'
    },
    {
      title: 'Bill References',
      teaser: 'Collated, categorized sources per bill.',
      details: 'We aggregate hearings, fiscal notes, think-tank pieces, and reporting; then tag by category (finance, healthcare, environment, etc.) so you can filter and jump straight to what matters.'
    },
    {
      title: 'Bill AI Reasoning',
      teaser: 'Scoring transparency + confidence.',
      details: 'Drill into why a score landed where it did, including which factors carried weight. Confidence expresses model certainty from 0–100 based on evidence quality and agreement.'
    },
    {
      title: 'Sort by Issue/Category',
      teaser: 'Rank legislators and bills by issue performance.',
      details: 'Slice results by issue clusters (e.g., Energy, Defense). Rankings use comparable, normalized vectors so cross-bill sorting remains fair even when text lengths and contexts vary.'
    },
    {
      title: 'Bill Text Comparison',
      teaser: 'Compare how scores evolve between versions',
      details: 'Unique AI analysis tells you exactly whats changed between bill text versions, and how the overall impact evaluation is affected.'
    },
    {
      title: 'Choose Your AI model',
      teaser: 'Compare and contrast models',
      details: 'Run your own AI analysis using a model of your choice! Compare and constrast how various models interpret bills over time.'
    },
    {
      title: 'Large Bill Breakdown',
      teaser: 'See how AI reads large bills',
      details: 'Larger bills are split up into sections and evaluated in chunks. These analyses are recombined at the end to produce the final analysis. See the analysis at each section.'
    },
    {
      title: 'Reprocess a Bill (on-demand)',
      teaser: 'Rerun with new info',
      details: 'Kick off a fresh run if a fiscal note drops. We version runs so you can compare deltas and see what changed.'
    },
    {
      title: 'Process Unreleased Bill (on-demand)',
      teaser: 'Upload your own bill text and preview a score.',
      details: 'Upload draft or amended text to estimate impacts before introduction. Output includes a provisional score, reference suggestions, and risk flags to investigate.'
    },
    {
      title: 'Alerts & Notifications',
      teaser: 'Get alerts by category or score threshold.',
      details: 'Stay in the loop with configurable triggers—new bill introduced, score above/below X, or key committee actions.'
    },
    {
      title: 'Historical Sessions Access',
      teaser: 'Browse bills and legislators from past sessions.',
      details: 'Open any prior session to view bill pages, legislator profiles, and scores exactly as they existed for that year. Use cross-session search and deep links to compare a legislator’s record or revisit a bill from its original session.',
    },
    {
      title: 'Historical Analytics & Trends',
      teaser: 'Time-series score movements and version timelines.',
      details: 'See score deltas over time with overlays for amendments, committee actions, fiscal notes, and media events. Compare across sessions, drill into “what changed & why,” and export CSVs for offline analysis.',
      soon: true
    },
    {
      title: 'Full State Legislature Data',
      teaser: 'All 50 states in one view.',
      details: 'Explore state bills with the same tools—scores, references, and change diffs—rolled out statewide as datasets finalize.',
      soon: true
    },
    {
      title: 'Legislator Media Coverage',
      teaser: 'Dig deep into legislator media coverage',
      details: 'Track legislator media coverage the same way we track legislator bill interactions. Dive into historical content. Track and score reputation of major media outlets.',
      soon: true
    },
    {
      "title": "Voter Score Card",
      "teaser": "Know exactly what’s on your ballot.",
      "details": "A personalized, easy-to-follow guide that breaks down every race, referendum, and amendment on your ballot. Includes candidate grades, summaries, and quick comparison views for confident voting.",
      soon: true
    },
    {
      "title": "Referendums",
      "teaser": "Understand ballot measures at a glance.",
      "details": "Get clear, neutral explanations of state and local referendums with AI-generated pros, cons, fiscal impacts, and historical context—all designed to help you make informed choices.",
      soon: true
    },
    {
      "title": "Judges",
      "teaser": "Transparency for the bench.",
      "details": "Objective profiles and performance metrics for judges, including ruling consistency, reversal rates, and topic trends, presented in plain language for every voter.",
      soon: true
    },
  ];

  constructor(private meta: Meta, private titleService: Title, public dialog: MatDialog, public config: ConfigService) { }

  ngOnInit(): void {
    this.updateMetaTags();
  }

  updateMetaTags(): void {
    const pageTitle = "Sign Up - PoliScore: " + this.config.getTagline();
    const description = "Support the PoliScore mission and unlock full access to expert tooling.";
    // const pageUrl = `https://poliscore.us/signup`;

    this.titleService.setTitle(pageTitle);
    
    this.meta.updateTag({ property: 'og:title', content: pageTitle });
    this.meta.updateTag({ property: 'og:description', content: description });
    // this.meta.updateTag({ property: 'og:url', content: pageUrl });
    // this.meta.updateTag({ property: 'og:image', content: imageUrl });
    // this.meta.updateTag({ property: 'og:type', content: 'website' });

    // // Twitter meta tags (optional)
    // this.meta.updateTag({ name: 'twitter:card', content: 'summary_large_image' });
    // this.meta.updateTag({ name: 'twitter:title', content: pageTitle });
    // this.meta.updateTag({ name: 'twitter:description', content: pageDescription });
    // this.meta.updateTag({ name: 'twitter:image', content: imageUrl });
  }

  goHome() {
    window.location.href = '/';
  }

  onSignup() {
    this.flow.start(environment.stripe.productPremium);
  }

  navigate(link: string | any[]) {
    const a = document.createElement('a');
    a.href = Array.isArray(link) ? link.join('/') : (link as string);
    document.body.appendChild(a); a.click(); a.remove();
  }
}

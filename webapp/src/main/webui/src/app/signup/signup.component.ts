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
import { Subscription } from 'rxjs';
import { EntitlementService } from '../billing/entitlement.service';
import { LabsService } from '../service/labs.service';
import { TrackClickDirective } from '../track-click.directive';

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
  imports: [CommonModule, RouterModule, TrackClickDirective],
  templateUrl: './signup.component.html',
  styleUrl: './signup.component.scss'
})
export class SignupComponent implements OnInit {
  @Input() headline = 'Become A Founding Member';
  @Input() headlineDescriptor = 'Join a growing community of policy professionals using AI to strengthen democratic accountability. Founding Members unlock premium tools for analyzing bills, understanding real-world impact, tracking legislative changes, and surfacing high-quality sources—all designed to empower advocacy groups, researchers, and legislative staff. Enroll now to be grandfathered into a $5/month plan for life as we continue expanding advanced policy-analysis features.';

  @Input() signupRouterLink: string | any[] = '/signup';
  @Input() loginRouterLink: string | any[] = '/login';
  @Input() termsRouterLink: string | any[] = '/terms';
  @Input() privacyRouterLink: string | any[] = '/privacy';

  auth = inject(AuthService);
  private flow = inject(PurchaseFlowService);

  private sub?: Subscription;
  entitlement = inject(EntitlementService);
  isLoggedIn: boolean = false;

  labs = inject(LabsService);

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
      details: 'Slice results by issues (e.g., Energy, Defense) to see which legislators or bills are best (or worst) for various sectors or issues.'
    },
    {
      title: 'Historical Access',
      teaser: 'View bills and legislators from previous sessions',
      details: 'Comb through history to view legislators and bills from previous legislative sessions (where data is available).'
    },
    {
      title: 'Legislative Session Statistics',
      teaser: 'View classic PoliScore stats aggregated up to the political parties',
      details: 'PoliScore\'s bill statistics are aggregated for you up to the political parties, and are available for each historical legislative session, allowing you to see how the poltiical parties change over time.'
    },
    {
      title: 'Bill Text Comparison',
      teaser: 'Compare how scores evolve between versions',
      details: 'Unique AI analysis tells you exactly whats changed between bill text versions, and how the overall impact evaluation is affected.',
      soon: true
    },
    {
      title: 'Choose Your AI model',
      teaser: 'Compare and contrast models',
      details: 'Run your own AI analysis using a model of your choice! Compare and constrast how various models interpret bills over time.',
      soon: true
    },
    {
      title: 'Large Bill Breakdown',
      teaser: 'See how AI reads large bills',
      details: 'Larger bills are split up into sections and evaluated in chunks. These analyses are recombined at the end to produce the final analysis. See the analysis at each section.',
      soon: true
    },
    {
      title: 'Reprocess a Bill (on-demand)',
      teaser: 'Rerun with new info',
      details: 'Kick off a fresh run if a fiscal note drops. We version runs so you can compare deltas and see what changed.',
      soon: true
    },
    {
      title: 'Process Unreleased Bill (on-demand)',
      teaser: 'Upload your own bill text and preview a score.',
      details: 'Upload draft or amended text to estimate impacts before introduction. Output includes a provisional score, reference suggestions, and risk flags to investigate.',
      soon: true
    },
    {
      title: 'Alerts & Notifications',
      teaser: 'Get alerts by category or score threshold.',
      details: 'Stay in the loop with configurable triggers—new bill introduced, score above/below X, or key committee actions.',
      soon: true
    },
    {
      title: 'Historical Sessions Access',
      teaser: 'Browse bills and legislators from past sessions.',
      details: 'Open any prior session to view bill pages, legislator profiles, and scores exactly as they existed for that year. Use cross-session search and deep links to compare a legislator’s record or revisit a bill from its original session.',
      eventually: true
    },
    {
      title: 'Historical Analytics & Trends',
      teaser: 'Time-series score movements and version timelines.',
      details: 'See score deltas over time with overlays for amendments, committee actions, fiscal notes, and media events. Compare across sessions, drill into “what changed & why,” and export CSVs for offline analysis.',
      eventually: true
    },
    {
      title: 'Full State Legislature Data',
      teaser: 'All 50 states in one view.',
      details: 'Explore state bills with the same tools—scores, references, and change diffs—rolled out statewide as datasets finalize.',
      eventually: true
    },
    {
      title: 'Legislator Media Coverage',
      teaser: 'Dig deep into legislator media coverage',
      details: 'Track legislator media coverage the same way we track legislator bill interactions. Dive into historical content. Track and score reputation of major media outlets.',
      eventually: true
    },
    {
      "title": "Voter Score Card",
      "teaser": "Know exactly what’s on your ballot.",
      "details": "A personalized, easy-to-follow guide that breaks down every race, referendum, and amendment on your ballot. Includes candidate grades, summaries, and quick comparison views for confident voting.",
      eventually: true
    },
    {
      "title": "Referendums",
      "teaser": "Understand ballot measures at a glance.",
      "details": "Get clear, neutral explanations of state and local referendums with AI-generated pros, cons, fiscal impacts, and historical context—all designed to help you make informed choices.",
      eventually: true
    },
    {
      "title": "Judges",
      "teaser": "Transparency for the bench.",
      "details": "Objective profiles and performance metrics for judges, including ruling consistency, reversal rates, and topic trends, presented in plain language for every voter.",
      eventually: true
    },
  ];

  constructor(private meta: Meta, private titleService: Title, public dialog: MatDialog, public config: ConfigService) { }

  ngOnInit(): void {
    this.updateMetaTags();

    this.sub = this.auth.isAuthenticated$.subscribe(s => {
      this.isLoggedIn = s;
     });
  }

  ngOnDestroy(): void {
    this.sub?.unsubscribe();
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

  onWantFeature(f: Feature) {
    // TODO: call backend
    // this.featureService.vote(f.id).subscribe();

    if (this.isLoggedIn) {
      alert(`Thanks for helping us prioritize new features!`);
      this.labs.requestFeature(f.title).subscribe();
    } else {
      // alert(`Log in or sign up to request a feature.`);
      this.dialog.open(LogInDialogComponent);
    }
  }

  showMe() {
    window.location.href = '/legislators';
  }

  goHome() {
    window.location.href = '/#how-it-works';
  }

  onSignup() {
    this.flow.start(environment.stripe.productPremium);
  }

  navigate(link: string | any[]) {
    const a = document.createElement('a');
    a.href = Array.isArray(link) ? link.join('/') : (link as string);
    document.body.appendChild(a); a.click(); a.remove();
  }

  get availableFeatures() {
    return this.features.filter(f => !f.soon && !f.eventually);
  }

  get comingSoonFeatures() {
    return this.features.filter(f => f.soon);
  }

  get longTermFeatures() {
    return this.features.filter(f => f.eventually);
  }

}

@Component({
  selector: 'not-logged-in-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatButtonModule],
  template: `
    <div class="login-dialog">
      <h2 mat-dialog-title>Log in to request features</h2>

      <mat-dialog-content>
        <p class="login-dialog__lead">
          Create a free account or log in so we can link your feedback to your profile.
        </p>

        <!-- <div class="login-dialog__highlight">
          <strong>Why log in?</strong>
          <ul>
            <li>Track the status of your feature requests</li>
            <li>Get notified when we ship something you asked for</li>
            <li>Help us prioritize what matters most to you</li>
          </ul>
        </div> -->

        <div *ngIf="data?.disclaimerComponent" class="login-dialog__disclaimer">
          <ng-container *ngComponentOutlet="data.disclaimerComponent"></ng-container>
        </div>
      </mat-dialog-content>

      <mat-dialog-actions align="end">
        <button mat-button (click)="onClose()">Not now</button>
        <button mat-flat-button color="primary" (click)="onLogIn()">
          Log in / Sign up
        </button>
      </mat-dialog-actions>
    </div>
  `,
  styles: [`
    .login-dialog {
      max-width: 480px;
    }

    .login-dialog__lead {
      margin: 0 0 12px;
      font-size: 14px;
      line-height: 1.5;
    }

    .login-dialog__highlight {
      padding: 12px 14px;
      border-radius: 8px;
      background: #f3f0ff; /* soft purple-ish */
      border-left: 4px solid #7b61ff;
      font-size: 13px;
    }

    .login-dialog__highlight ul {
      margin: 6px 0 0;
      padding-left: 18px;
    }

    .login-dialog__highlight li {
      margin-bottom: 4px;
    }

    .login-dialog__disclaimer {
      margin-top: 12px;
      font-size: 11px;
      color: rgba(0, 0, 0, 0.6);
    }

    mat-dialog-content {
      padding-top: 0;
      padding-bottom: 8px;
    }

    mat-dialog-actions {
      margin-top: 8px;
      padding-bottom: 4px;
    }
  `]
})
export class LogInDialogComponent {
  auth = inject(AuthService);

  constructor(
    @Inject(MAT_DIALOG_DATA) public data: { large: string; disclaimerComponent: any },
    public dialogRef: MatDialogRef<LogInDialogComponent>
  ) {}

  onLogIn(): void {
    this.auth.login();
  }

  onClose(): void {
    this.dialogRef.close();
  }
}

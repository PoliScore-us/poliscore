import { AfterViewInit, Component, ElementRef, Inject, inject, Input, ViewChild } from '@angular/core';
import { CommonModule } from '@angular/common';
import { RouterModule } from '@angular/router';
import { AuthService } from '../auth/auth.service';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { MatButtonModule } from '@angular/material/button';

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
export class SignupComponent {
  // @Input() headline = 'Create your PoliScore account';
  @Input() headline = 'Labs';

  // @Input() headlineDescriptor = 'Join now to unlock expert agentic AI analysis for bills and legislators.';
  @Input() headlineDescriptor = "Get your beakers out, we're working on some exciting things around here.";

  @Input() signupRouterLink: string | any[] = '/signup';
  @Input() loginRouterLink: string | any[] = '/login';
  @Input() termsRouterLink: string | any[] = '/terms';
  @Input() privacyRouterLink: string | any[] = '/privacy';

  constructor(public dialog: MatDialog) {}

  auth = inject(AuthService);

  // You can tweak wording here any time.
  features: Feature[] = [
    {
      title: 'Bill Expert Analysis',
      teaser: 'Full agentic analysis with embedded source references.',
      details: 'See structured, multi-step reasoning that ties claims to citations. We identify arguments, extract key claims, and attach links back to primary sources for traceability.',
      completed: true
    },
    {
      title: 'Bill References',
      teaser: 'Collated, categorized sources per bill.',
      details: 'We aggregate hearings, fiscal notes, think-tank pieces, and reporting; then tag by category (finance, healthcare, environment, etc.) so you can filter and jump straight to what matters.',
      completed: true
    },
    {
      title: 'Bill AI Reasoning',
      teaser: 'Scoring transparency + confidence.',
      details: 'Drill into why a score landed where it did, including which factors carried weight. Confidence expresses model certainty from 0–100 based on evidence quality and agreement.',
      completed: true
    },
    {
      title: 'Sort by Issue/Category',
      teaser: 'Rank legislators and bills by issue performance.',
      details: 'Slice results by issue clusters (e.g., Energy, Defense). Rankings use comparable, normalized vectors so cross-bill sorting remains fair even when text lengths and contexts vary.',
      completed: true
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
      title: 'Full State Legislature Data',
      teaser: 'All 50 states in one view.',
      details: 'Explore state bills with the same tools—scores, references, and change diffs—rolled out statewide as datasets finalize.',
      eventually: true
      // soon: true
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

  joinWaitlist() {
    this.dialog.open(DisclaimerDialogSubscribeComponent, {
      panelClass: 'ps-subscribe'
    });
  }

  goHome() {
    window.location.href = '/';
  }

  sendEmail() {
    this.dialog.open(DisclaimerDialogContactUsComponent);
  }

  onSignup() {
    alert("TODO");
  }

  navigate(link: string | any[]) {
    const a = document.createElement('a');
    a.href = Array.isArray(link) ? link.join('/') : (link as string);
    document.body.appendChild(a); a.click(); a.remove();
  }
}

@Component({
  selector: 'disclaimer-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatButtonModule],
  template: `
    <div mat-dialog-content>
        <h1>Recommend A Feature</h1>

        <p>Send us an email! We want to hear from you.</p>

        <p><a href="mailto:contact@poliscore.us">contact&#64;poliscore.us</a></p>
    </div>
    <div mat-dialog-actions align="center">
      <button mat-button (click)="onClose()">Close</button>
    </div>
  `,
})
export class DisclaimerDialogContactUsComponent {
    constructor(
      @Inject(MAT_DIALOG_DATA) public data: { large: string, disclaimerComponent: any },
      public dialogRef: MatDialogRef<DisclaimerDialogContactUsComponent>
    ) {}
  
    onClose(): void {
      this.dialogRef.close();
    }
}

@Component({
  selector: 'disclaimer-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatButtonModule],
  template: `
    <div #container mat-dialog-content style="overflow: clip;">
        <iframe width="580" [height]="iframeHeight" src="https://2d35a37e.sibforms.com/serve/MUIFAEfKmRMiRH1IIdFNniSQ0p_kxWiMePAraiMtk1gUb7p4B0wspA-LK-skb80JRm2Ifmo9NbTdx5R_hk96Xb1SpsG63Gx22w-lq81DDd05PRRgvQc9NYb2qTHmGwZE7FqZ98e7y3-dxIscT184MJPgJ276sk2FSJxWBeD3IFaKzNi3XuwO7NH8EsC5VPZ94UYKijEEfjnWZ4Sq" frameborder="0" scrolling="auto" allowfullscreen style="display: block;margin-left: auto;margin-right: auto;max-width: 100%;"></iframe>
    </div>
    <div mat-dialog-actions align="center">
      <button mat-button (click)="onClose()">Close</button>
    </div>
  `,
})
export class DisclaimerDialogSubscribeComponent implements AfterViewInit {
    @ViewChild('frame') frame!: ElementRef<HTMLIFrameElement>;
    @ViewChild('container') container!: ElementRef<HTMLElement>;
    public iframeHeight = 380;

    constructor(
      @Inject(MAT_DIALOG_DATA) public data: { large: string, disclaimerComponent: any },
      public dialogRef: MatDialogRef<DisclaimerDialogSubscribeComponent>
    ) {}

    ngAfterViewInit() {
      const w = Math.min(580, this.container.nativeElement.clientWidth);
      const max = Math.floor(window.innerHeight * 0.9); // keep within viewport

      if (w <= 360)
        this.iframeHeight = 800;
    }
  
    onClose(): void {
      this.dialogRef.close();
    }
}

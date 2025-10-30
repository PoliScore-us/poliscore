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
  soon?: boolean;
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
  @Input() headline = 'PoliScore Labs';

  // @Input() headlineDescriptor = 'Join now to unlock expert agentic AI analysis for bills and legislators.';
  @Input() headlineDescriptor = 'Expert tooling is coming soon to PoliScore!';

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
      title: 'Reprocess a Bill',
      teaser: 'Rerun with new info or a different model.',
      details: 'Kick off a fresh run if a fiscal note drops or you want a second opinion from another model. We version runs so you can compare deltas and see what changed.'
    },
    {
      title: 'Process Unreleased Bill (on-demand)',
      teaser: 'Upload your own bill text and preview a score.',
      details: 'Upload draft or amended text to estimate impacts before introduction. Output includes a provisional score, reference suggestions, and risk flags to investigate.'
    },
    {
      title: 'See Differences Between Bill Versions',
      teaser: 'Compare exactly what text AI analyzed.',
      details: 'Line-by-line diff highlights insertions/deletions and re-computes the likely impact. You’ll always know which version produced a given score.'
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
      // soon: true
    }
  ];

  joinWaitlist() {
    this.dialog.open(DisclaimerDialogSubscribeComponent, {
      panelClass: 'ps-subscribe'
    });
  }

  goHome() {
    window.location.href = '/';
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

import { CommonModule, isPlatformBrowser } from '@angular/common';
import { AfterViewInit, Component, ElementRef, Inject, PLATFORM_ID, ViewChild } from '@angular/core';
import { MatButtonModule } from '@angular/material/button';
import { MAT_DIALOG_DATA, MatDialog, MatDialogModule, MatDialogRef } from '@angular/material/dialog';
import { ConfigService } from '../config.service';
import { Meta, Title } from '@angular/platform-browser';
import { RouterModule } from '@angular/router';

@Component({
  selector: 'app-promo',
  standalone: true,
  imports: [RouterModule, CommonModule, MatDialogModule],
  templateUrl: './promo.component.html',
  styleUrl: './promo.component.scss'
})
export class PromoComponent {
  public isPreload = true;
  
    public donateBarHidden = true;
  
    constructor(public config: ConfigService, public dialog: MatDialog, private meta: Meta, private titleService: Title, @Inject(PLATFORM_ID) private platformId: Object) { }
  
    ngOnInit(): void {
      this.updateMetaTags();
      setTimeout(() => {
        this.isPreload = false;
      }, 100);

      if (isPlatformBrowser(this.platformId)) {
        setTimeout(() => {
          document.querySelector('#header .content')?.classList.add('visible');
          document.querySelectorAll('#header button')?.forEach(el => el.classList.add('visible'));
        }, 100);
      }
    }
  
    onScroll(e: any) {
      const el = e.target;
  
      let scrollAmt = el.offsetHeight + el.scrollTop;
  
      if (scrollAmt >= (el.scrollHeight * 0.30) && scrollAmt <= (el.scrollHeight - 1000)) {
        this.donateBarHidden = false;
      } else {
        this.donateBarHidden = true;
      }
    }
  
    public captureEmailForm(): void {
      window.location.href = "https://2d35a37e.sibforms.com/serve/MUIFABzv_pK1_YgaT0O9h369Fe89iBz1lmE63oAo2cuHjvcQmATp3Juz4BudHm6zdwwIAraE4YGla-0G121m2DEC-RQP_YUO98T5a5ciR33HDYJnFAyYATNoiO6H5PQWTPfkfYJOae2Rx_J52Ag3H4B8I--ljBdvugyb0oQdfxaOFEamGNOGHPfBEaEA-yFacsvAN7oZRyaOXKcB";
    }
  
    clickPrivacyPolicy() {
      this.dialog.open(DisclaimerDialogComponent);
    }

    clickSubscribe() {
      this.dialog.open(DisclaimerDialogSubscribeComponent, {
        panelClass: 'ps-subscribe'
      });
    }

    clickContact() {
      this.dialog.open(DisclaimerDialogContactUsComponent);
    }
  
    updateMetaTags(): void {
      let year = this.config.getYear();
  
      let pageTitle = "PoliScore: " + this.config.getTagline();
      const pageDescription = this.config.appDescription();
      const pageUrl = "https://poliscore.us/" + year + "/about";
      const imageUrl = 'https://poliscore.us/' + year + '/images/poliscore-word-whitebg.png';
  
      this.titleService.setTitle(pageTitle);
      
      this.meta.updateTag({ property: 'og:title', content: pageTitle });
      this.meta.updateTag({ property: 'og:description', content: pageDescription });
      this.meta.updateTag({ property: 'og:url', content: pageUrl });
      this.meta.updateTag({ property: 'og:image', content: imageUrl });
      this.meta.updateTag({ property: 'og:type', content: 'website' });
  
      // Twitter meta tags (optional)
      this.meta.updateTag({ name: 'twitter:card', content: 'summary_large_image' });
      this.meta.updateTag({ name: 'twitter:title', content: pageTitle });
      this.meta.updateTag({ name: 'twitter:description', content: pageDescription });
      this.meta.updateTag({ name: 'twitter:image', content: imageUrl });
    }
  }
  
@Component({
  selector: 'disclaimer-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatButtonModule],
  template: `
    <div mat-dialog-content>
        <p>PoliScore is committed to protecting your privacy. We use Google Analytics with default settings to collect basic, non-personal data such as the pages you visit and the links you click. This data helps us understand how users interact with our website and improve its functionality. We do not collect or store personally identifiable information, nor do we sell or share any data with third parties.</p>

        <br/>
        <p>Google Analytics processes this information in accordance with <a href="https://policies.google.com/privacy">their Privacy Policy</a>. If you prefer not to be tracked, you can opt out using the <a href="https://tools.google.com/dlpage/gaoptout/">Google Analytics Opt-Out Browser Add-on</a> or by adjusting your browser settings to block tracking scripts. By using our website, you agree to the terms of this policy.</p>
    </div>
    <div mat-dialog-actions align="center">
      <button mat-button (click)="onClose()">Close</button>
    </div>
  `,
})
export class DisclaimerDialogComponent {
    constructor(
      @Inject(MAT_DIALOG_DATA) public data: { large: string, disclaimerComponent: any },
      public dialogRef: MatDialogRef<DisclaimerDialogComponent>
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
    <div mat-dialog-content>
        <h1>We want to hear from you!</h1>

        <p>The admins at PoliScore.us can be contacted via the email:</p>

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
        <iframe width="580" [height]="iframeHeight" src="https://2d35a37e.sibforms.com/serve/MUIFABRydGpQl2fs-e6DC-0XusqdgiRHtkyqEXvWJ4Fbef4ZQN_qqlkXY80ZJbCoIZW_vKzZfaUqhTPd-FhYxPeYy3v68zs5KNbdNkb_ag_ym5CWHgYGe6aftbrQnGxxf8YyL2j4S12r-rZiYygk84clqFu0BM-8NTAH5lQGoxmIAzXzKg1qiE-Aa0R7SN5a1z3GXlAR6_-neIm4" frameborder="0" scrolling="auto" allowfullscreen style="display: block;margin-left: auto;margin-right: auto;max-width: 100%;"></iframe>
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

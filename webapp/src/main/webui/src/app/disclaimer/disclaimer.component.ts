import { Component, Inject, Input, TemplateRef, ViewChild } from '@angular/core';
import { MatDialog, MatDialogModule, MAT_DIALOG_DATA, MatDialogRef } from '@angular/material/dialog';
import { CommonModule } from '@angular/common';
import { MatButtonModule } from '@angular/material/button';
import { InterpretationMetadata } from '../model';

@Component({
  selector: 'disclaimer',
  standalone: true,
  imports: [CommonModule, MatDialogModule],
  templateUrl: './disclaimer.component.html',
  styleUrl: './disclaimer.component.scss'
})
export class DisclaimerComponent {
  @Input() public small: string = "";

  @Input() public large: string = "";

  @Input() public positionBelow: boolean = false;

  @Input() public metadata!: InterpretationMetadata;

  @Input() public isBill = true;

  @ViewChild('disclaimerBodyTpl', { static: true })
  disclaimerBodyTpl!: TemplateRef<any>;

  public tooltipVisible = true;

  constructor(public dialog: MatDialog) {}

  openDialog(): void {
    this.tooltipVisible = false;
    this.dialog.open(DisclaimerDialogComponent, {
      data: {
        large: this.large,
        metadata: this.metadata,
        isBill: this.isBill,
        tpl: this.disclaimerBodyTpl,
        disclaimerComponent: this,
      },
      width: '80vw',
      maxWidth: '95vw',
      minWidth: '80vw',
      maxHeight: '90vh',
      panelClass: 'ps-disclaimer',
      autoFocus: false
    });
  }

  getLargeForTooltip() {
    if (!this.large) return "";

    if (this.large.length > 700) {
      return this.large.substring(0, 700) + "\n\n... click for more ...\n";
    } else {
      return this.large;
    }
  }
}

@Component({
  selector: 'disclaimer-dialog',
  standalone: true,
  imports: [CommonModule, MatDialogModule, MatButtonModule],
  template: `
    <div mat-dialog-content>
      <ng-container
        [ngTemplateOutlet]="data.tpl"
        [ngTemplateOutletContext]="{
          large: data.large,
          metadata: data.metadata,
          isBill: data.isBill,
          mode: 'dialog'
        }"
      ></ng-container>
    </div>
    <div mat-dialog-actions align="center">
      <button mat-button (click)="onClose()">Close</button>
    </div>
  `,
})
export class DisclaimerDialogComponent {
  constructor(
    @Inject(MAT_DIALOG_DATA)
    public data: {
      large: string;
      metadata: InterpretationMetadata;
      isBill: boolean;
      tpl: TemplateRef<any>;
      disclaimerComponent: any;
    },
    public dialogRef: MatDialogRef<DisclaimerDialogComponent>
  ) {}

  onClose(): void {
    this.data.disclaimerComponent.tooltipVisible = true;
    this.dialogRef.close();
  }
}

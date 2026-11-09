import { Component } from '@angular/core';
import { MatToolbarModule } from '@angular/material/toolbar';
import { RouterOutlet } from '@angular/router';
import { TEXTE } from './gemeinsam/texte.de-CH';

@Component({
  imports: [MatToolbarModule, RouterOutlet],
  selector: 'app-root',
  styleUrl: './app.scss',
  templateUrl: './app.html',
})
export class App {
  protected readonly texte = TEXTE;
}

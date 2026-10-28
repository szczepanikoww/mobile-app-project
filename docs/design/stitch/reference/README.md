# Stitch references for the exercise database screens

These screenshots come from the [Stitch project](https://stitch.google.com/projects/10185753988111686080) (fetched 2026-10-28). Use them for the visual language only. [CLAUDE.md](../../../../CLAUDE.md) still decides what we build and wins where the two disagree. The exercise library, exercise detail and exercise form have no Stitch screen of their own. This page lists which existing patterns they reuse, so they look like the rest of the app.

| File | Stitch screen | Why it is relevant |
|---|---|---|
| [guest-trenerzy.png](guest-trenerzy.png) | Trenerzy – gość | Browsable catalogue: search, filter chips with dropdowns, list rows with an image. The closest match to the library. |
| [trainer-klienci.jpg](trainer-klienci.jpg) | Klienci (trainer) | Trainer-side list: search with clear, chip filters with counts, header primary action, status chips. The trainer nav shows a central **"+ PLAN"** button (relevant to Decision D1). |
| [client-trening-na-zywo.png](client-trening-na-zywo.png) | Trening na żywo | Exercise header (media + name + "muscle · equipment" + info button), set rows, offline save status. |
| [guest-profil-trenera.png](guest-profil-trenera.png) | Profil trenera – gość | Detail-page structure: hero media, tag chips, sections with leading icons, rows with thumbnails. |
| [client-postepy.png](client-postepy.png) | Postępy | Segmented pill tabs, the "Wybrane ćwiczenie" selector field, compact rows with a thumbnail. |
| [trainer-pulpit-jasny.jpg](trainer-pulpit-jasny.jpg) | Pulpit Trenera – Jasny | General trainer look: surfaces, spacing, status chips, bottom navigation. |
| [../plan-creator/kreator-planu.jpg](../plan-creator/kreator-planu.jpg) | Kreator planu | The plan editor the picker is opened from. |

## Patterns to reuse

### S3 Exercise library / picker

| Element | Stitch source | How to apply it |
|---|---|---|
| Header | Klienci: title plus a pill primary action at the top right ("Zaproś klienta"); Postępy: "+ Dodaj pomiar" | Title "Ćwiczenia"; in browse mode a "+ Nowe ćwiczenie" pill action at the top right. In pick mode that slot is empty, and the primary "Dodaj (n)" sits fixed at the bottom. |
| Search | Trenerzy and Klienci: full-width white pill field, leading search icon, clear "×" | Use it as is: "Szukaj ćwiczenia, partii, sprzętu…". Drop the microphone icon from Trenerzy. |
| Scope switch | Postępy: pill tabs "Ćwiczenia / Pomiary / …" | "Wszystkie / Moje". Use this instead of a "Moje" filter chip. |
| Filters | Trenerzy: chips with a chevron, "Cel ▾", "Siłownia ▾" | "Partia mięśniowa ▾", "Sprzęt ▾"; each opens a bottom sheet. Stitch fills an active chip with bright orange and white text, which fails contrast (CLAUDE.md section 4). Use the soft accent `#FFF0E6` with dark text `#572000`, or the strong brand `#A04100` with white text. |
| Count | Trenerzy: "38 dostępnych" pill beside the title | Supporting text above the list: "124 ćwiczenia". Not a pill. |
| List row | Postępy "Ostatnie treningi" (compact row with a thumbnail); Trenerzy (image + title + subtitle + tags) | The compact version: 56 dp thumbnail with a 16 dp radius, name in the item-title style, supporting line "Klatka piersiowa · Sztanga", and a chevron (browse) or checkbox (pick). No ratings, prices or tag chips in the row. |
| Row grouping | Stitch puts every row in its own shadowed card | Rows share **one** white surface with dividers, grouped under section headers ("Moje ćwiczenia", "A–Z"). CLAUDE.md section 3 asks for this. |
| Own exercise | Klienci status chips ("Aktywny", "Zmiana terminu") | A small neutral "Własne" chip. "Oczekuje na synchronizację" uses the warning chip style with an icon. |

Not taken from Trenerzy: the list/grid toggle, the sort control (A–Z is fixed), the star ratings, and the quiz promo banner.

### S4 Exercise detail

| Element | Stitch source | How to apply it |
|---|---|---|
| Media | Trening na żywo: 16:9 media card with a 24 dp radius and a play button over it; the "Wideo wzorcowe" chip | The dataset images (start and end position) as a 2-image carousel. Once `demoMediaId` exists, show the video with a play button. A missing image gets a neutral placeholder with an icon. |
| Title block | Trening na żywo: "Wyciskanie sztangi na ławce" + "Klatka piersiowa · Ławka pozioma" | The same: name in the screen-title style, primary muscle · equipment as supporting text. The **(i)** button on the logger opens this screen. |
| Muscles | Profil trenera: "Specjalizacje" chips, one of them highlighted | Primary muscles use soft-accent chips; secondary muscles use neutral outlined chips. |
| Sections | Profil trenera: section titles with a leading orange icon ("O moim podejściu", "Gdzie trenuję") | Sections "Mięśnie", "Sposób zapisu", "Instrukcja" (numbered steps). Icons are optional. Use the section-title style, not small uppercase labels. |
| Actions | Profil trenera: one fixed full-width primary button at the bottom | Pick mode: "Dodaj do dnia". Own exercise: "Edytuj" in the top bar and "Ukryj" in the overflow menu. |

### S5 Exercise form

| Element | Stitch source | How to apply it |
|---|---|---|
| Selector fields | Postępy: "Wybrane ćwiczenie" field (small label + value + chevron) | Primary muscle, equipment and secondary muscles. Tap opens a bottom sheet with options. Keep the label visible above the value at all times. Drop the decorative icon circle. |
| Tracking type | Postępy pill tabs | A segmented choice with 4 options. It wraps to two rows on small screens and at large font sizes. |
| Sync status | Trening na żywo: "Offline · zapisano na telefonie" with a cloud-check icon | The same line on S4 and S5 for a custom exercise that has not synced yet. |
| Primary button | Orange pill buttons across all screens | One primary "Utwórz ćwiczenie", fixed above the keyboard. Use white text on `#A04100`, not on `#FF6B00`, for contrast. |

### Shared with the plan editor and logger

- **Set row** (Trening na żywo): a set-number circle, a type chip ("Rozgrzewka" / "Robocza"), and value boxes with the unit as a small grey suffix ("60 kg", "10 powt.", "@6 RPE"). The plan editor's set table (S2) uses the same component. The Trening na żywo layout does not clip, unlike the Kreator planu mock.

## Do not copy

These appear across the references but are excluded by CLAUDE.md:

- corner arcs, glow and blur
- one shadowed card per row
- bright orange with small white text
- 10 px uppercase labels used for essential text
- duplicated headers with an avatar, chat and notification icons on detail screens
- ratings and "Zweryfikowany PRO" badges
- stock photos used purely for decoration

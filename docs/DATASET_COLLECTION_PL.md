# Zbieranie pilota datasetu — instrukcja samodzielna

Ten dokument dotyczy pilota, a nie jeszcze wielogodzinnego finalnego datasetu.
Najpierw sprawdzamy wszystkie pięć klas, oba miejsca mocowania i metadane. Po
analizie pilota dopiero zwiększamy liczbę godzin.

## Stan gotowości

- `xiao_unit_01` ma zweryfikowany firmware z commita `e744053` i kalibrację w
  `calibration/xiao_unit_01.json`.
- Druga fizyczna płytka musi mieć ten sam firmware, osobną pustą pamięć QSPI,
  osobny 30-sekundowy smoke test i własną kalibrację sześciopozycyjną.
- Każdy telefon używa najnowszej aplikacji i osobnego folderu docelowego.
- Nie wolno mieszać plików dwóch płytek w jednym folderze, ponieważ ich nazwy
  (`walking_0.csv` itd.) mogą się powtarzać.

## Dwie płytki i dwa telefony

Do czasu implementacji multi-BLE używaj jednego telefonu na jedną płytkę:

| Zestaw | Folder telefonu | Przykładowe początkowe miejsce |
| --- | --- | --- |
| `xiao_unit_01` + telefon A | `activity_tracker_xiao01` | nadgarstek |
| `xiao_unit_02` + telefon B | `activity_tracker_xiao02` | noga |

Podczas łączenia najpierw włącz tylko pierwszą płytkę i połącz telefon A.
Następnie włącz drugą i połącz telefon B. Obie reklamują obecnie tę samą nazwę,
więc ta kolejność zapobiega połączeniu telefonu z niewłaściwym egzemplarzem.

Nie przypisuj jednej płytki na zawsze do jednego miejsca. W połowie zbioru
zamień płytki miejscami i zapisz to w notatkach. Inaczej różnica między
modelami „nadgarstek” i „noga” może częściowo wynikać z różnic egzemplarzy IMU.

## Kalibracja drugiej płytki

Kalibrację można matematycznie policzyć po zebraniu surowych danych, ale jest
to ryzykowne. Wykonaj ją przed właściwym zbiorem, ponieważ:

- wykrywa wadliwy lub źle skonfigurowany IMU przed utratą wielu godzin pracy;
- bias żyroskopu i skala akcelerometru są zależne od egzemplarza;
- po zmianie obudowy, konfiguracji lub uszkodzeniu płytki nie da się pewnie
  odtworzyć wcześniejszego stanu.

Jeżeli wyjątkowo zbierzesz coś przed kalibracją, pozostaw CSV całkowicie surowe,
nie zmieniaj firmware, zakresów, ODR ani ułożenia płytki w obudowie i oznacz
wszystkie takie sesje jako `pre_calibration`. Nie jest to wariant zalecany.

Procedura dla drugiej płytki:

1. Odłącz pierwszą płytkę od USB i podłącz tylko drugą.
2. Upewnij się, że nie ma na niej potrzebnych plików; formatter kasuje całą QSPI.
3. Wgraj środowisko `seeed_xiao_nrf52840_sense_formatter` i poczekaj na
   `ok,format_completed`.
4. Wgraj `seeed_xiao_nrf52840_sense` z commita `e744053`.
5. Zainstaluj aktualny `app-debug.apk` na drugim telefonie.
6. Wybierz osobny folder, połącz BLE i nagraj 30 s nieruchomo. Wymagane:
   `Saved and CRC32 verified locally`.
7. Nagraj sześć przeciwnych ścian obudowy po około 60 s każda.
8. Nie rozpoczynaj właściwego porównania, dopóki pliki drugiej kalibracji nie
   zostaną sprawdzone i zapisane jako `xiao_unit_02`.

## Pilot do zebrania

Zbieraj oba miejsca jednocześnie, tę samą czynność i w tych samych warunkach.
Dla każdej klasy wykonaj trzy oddzielne sesje po około 3 minuty:

- `walking` — normalny chód, co najmniej dwie trasy/tempa;
- `running` — bezpieczne, jednostajne tempo, co najmniej dwie prędkości;
- `cycling` — bezpieczna trasa lub rower stacjonarny; nie obsługuj telefonu w
  ruchu;
- `sitting` — np. krzesło przy biurku i kanapa;
- `lying` — np. plecy i bok jako oddzielne sesje.

To daje około 45 minut materiału na miejsce mocowania. Po tym pilocie przerwij
zbieranie i zweryfikuj rozkłady, zakresy, etykiety i pipeline podziału danych.

## Procedura każdej pary sesji

1. Zamocuj obie obudowy ciasno i zawsze w tej samej orientacji. Zrób zdjęcie
   orientacji nadgarstka i nogi przed pierwszą sesją dnia.
2. Sprawdź, że oba telefony pokazują `Ready`, prawidłowy osobny folder i
   właściwą etykietę.
3. Uruchom nagrywanie na obu telefonach w odstępie kilku sekund.
4. Zostaw 10 s spokojnego początku, wykonaj aktywność przez około 3 minuty i
   zostaw 10 s spokojnego końca. Te brzegi zostaną później odcięte.
5. Zatrzymaj oba nagrania dopiero w bezpiecznym miejscu.
6. Na obu telefonach poczekaj na `Saved and CRC32 verified locally`.
7. Nie kasuj pliku lokalnego. Automatyczne kasowanie dotyczy wyłącznie
   zweryfikowanej kopii na płytce.
8. Natychmiast zapisz metadane sesji.

## Metadane obowiązkowe

Aktualna aplikacja wymaga przed `Start` wyboru `Wrist`/`Leg` oraz
`Left`/`Right`. Po zweryfikowaniu CSV zapisuje obok niego plik
`<nazwa>.csv.session.json` z aktywnością, miejscem i stroną mocowania, adresem
BLE płytki, identyfikatorem logicznej sesji, czasem rozpoczęcia, rozmiarem i
CRC32. Wszystkie segmenty utworzone przez automatyczny podział jednego nagrania
mają ten sam identyfikator sesji. CSV pozostaje bajtowo niezmieniony.

Pliki sprzed tej wersji aplikacji nie dostaną metadanych wstecznie. Dla nich
trzeba zachować dotychczasowe notatki ręczne.

Dla każdego pliku zapisz przynajmniej:

- dokładną nazwę pliku;
- alias płytki (`xiao_unit_01` lub `xiao_unit_02`);
- miejsce (`wrist` lub `leg`) i stronę ciała;
- aktywność;
- datę i przybliżoną godzinę;
- identyfikator osoby;
- warunki: wnętrze/zewnątrz, nawierzchnia, tempo, rower/krzesło/łóżko;
- czy mocowanie było poprawne i czy wystąpiła przerwa/błąd;
- czy sesja była przed kalibracją.

Nie polegaj na samej nazwie CSV — dwa urządzenia mogą wygenerować identyczną.
Na komputer kopiuj pliki do osobnych ścieżek według urządzenia i daty,
zachowując bajty CSV bez zmian.

## Zasady jakości i bezpieczeństwa

- Nie zmieniaj ODR 104 Hz, uśredniania do 52 Hz, zakresów ±16 g/±2000 dps,
  firmware ani orientacji montażu w środku pilota.
- Nie traktuj plików smoke, kalibracji ani testów technicznych jako datasetu.
- Nie poprawiaj ręcznie CSV i nie łącz sesji przed archiwizacją surowych plików.
- Po każdym dniu wykonaj co najmniej drugą kopię obu folderów telefonów.
- Podział train/validation/test będzie wykonywany całymi sesjami (i dniami),
  nigdy losowymi oknami z tego samego pliku, aby uniknąć leakage.
- Jeśli pojawi się `Device fault`, brak CRC, luka, brak automatycznego pobrania
  albo poluzowane mocowanie, oznacz sesję jako nieważną i nie kontynuuj godzinami.

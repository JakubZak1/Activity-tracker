# Dziennik obserwacji i ograniczeń projektu

Ten plik jest roboczym źródłem materiału do pracy magisterskiej. Zapisuje nie
tylko wyniki pozytywne, ale również awarie, ograniczenia eksperymentu i
obserwacje, które mogą wyjaśniać wyniki. Przed przeniesieniem tekstu do pracy
należy odróżniać cztery poziomy pewności:

- **Potwierdzone** — istnieje plik, log, test albo powtarzalna obserwacja.
- **Obserwacja użytkownika** — zauważone podczas testu, ale bez pełnego zapisu
  diagnostycznego.
- **Hipoteza** — prawdopodobne wyjaśnienie, które wymaga osobnego testu.
- **Do sprawdzenia** — zaplanowany test lub brakujący dowód.

## 1. Niezawodność rejestracji i BLE

### Uruchamianie loggera

- **Potwierdzone:** starszy firmware rozpoczynał zapis po podaniu zasilania. Po
  pozostawieniu urządzenia z baterią doprowadziło to do zapełnienia pamięci.
- **Wniosek projektowy:** aktualny firmware uruchamia się w stanie `Idle` i
  tworzy sesję dopiero po jawnej komendzie `record_start`. Jest to nie tylko
  cecha interfejsu, ale zabezpieczenie pamięci i danych.

### Utrata połączenia i ekran zablokowany

- **Potwierdzone:** utrata BLE nie zatrzymuje aktywnego nagrania. Po ponownym
  połączeniu aplikacja uzgadnia stan urządzenia komendą `status`.
- **Potwierdzone:** podczas prawie 20-minutowej próby telefon był zablokowany
  przez większość czasu, a około 15 segmentów zostało pobranych i
  zweryfikowanych.
- **Potwierdzone:** dłuższy test obejmował wielokrotne cykle zamknięcia segmentu,
  pobrania, kontroli CRC32, usunięcia kopii z płytki i wznowienia tej samej
  etykiety. Szczegóły i dokładne czasy znajdują się w
  `docs/hardware_validation_v5.md`.
- **Wniosek projektowy:** do transferu przy zablokowanym ekranie potrzebne były
  foreground service oraz częściowy wake lock Androida. Sam fakt zestawienia
  BLE nie gwarantuje, że system operacyjny pozwoli aplikacji wykonywać długie
  transfery w tle.

### Segmentacja, pobieranie i kasowanie

- **Potwierdzone:** zapis jest dzielony na segmenty, ponieważ pojedyncza długa
  sesja może przekroczyć bezpieczny zapas pamięci urządzenia.
- **Potwierdzone:** po zamknięciu segmentu firmware wstrzymuje próbkowanie,
  Android pobiera plik, sprawdza rozmiar i CRC32, a dopiero wtedy wysyła żądanie
  usunięcia kopii z płytki. Po poprawnym usunięciu zapis tej samej aktywności
  jest wznawiany.
- **Istotne znaczenie interfejsu:** `Delete` usuwa kopię na XIAO, a nie
  zweryfikowany plik w folderze telefonu. W początkowym teście zachowanie to
  wyglądało jak błąd, dopóki nie sprawdzono katalogu płytki.
- **Potwierdzone:** przerwany transfer może pozostawić plik `.part`. W jednym
  teście metadane częściowego pliku `lying_29.csv` były niekompletne; po
  poprawieniu obsługi odzyskiwania plik został ponownie pobrany.
- **Wniosek projektowy:** sam rozmiar pliku nie wystarcza do potwierdzenia
  poprawności. Finalizacja lokalnego CSV i zdalne kasowanie są dozwolone dopiero
  po zgodności rozmiaru oraz CRC32.

### Dwie płytki i identyfikacja danych

- **Potwierdzone:** jeden telefon utrzymuje dwa niezależne połączenia z
  urządzeniami:
  - Blue / `872F1832` — nadgarstek;
  - Green / `18EE26A8` — goleń.
- **Potwierdzone:** stabilny identyfikator występuje w nazwie BLE i prefiksie
  pliku, a komenda identyfikacyjna uruchamia LED właściwego koloru. Ogranicza to
  ryzyko zamiany urządzeń i pozwala dobrać właściwy profil kalibracji.
- **Obserwacja:** dwa pliki tej samej pary nie muszą mieć identycznej liczby
  bajtów ani próbek. Komendy start/stop docierają osobno, urządzenia mają
  niezależne zegary, a rozmiar tekstowego CSV zależy również od liczby znaków w
  wartościach. Wspólną próbę identyfikuje `paired_session_id`, nie identyczny
  rozmiar plików.

## 2. Tor pomiarowy IMU

### Nieudany wariant FIFO

- **Potwierdzone:** krótki test implementacji FIFO wyglądał prawidłowo, ale
  dłuższe nagranie ujawniło przesunięcie wzorca słów: osie zaczęły się mieszać,
  a nieruchomy żyroskop osiągał niefizyczne wartości. Pliki z tego wariantu są
  diagnostyczne i nie weszły do datasetu.
- **Wniosek:** krótki smoke-test nie wystarcza do oceny integralności długiego
  strumienia. Należy badać rozkład modułu przyspieszenia, spoczynek żyroskopu,
  ciągłość czasu i dłuższe przebiegi.
- **Potwierdzone rozwiązanie:** aktualny tor czyta kompletne ramki
  akcelerometr + żyroskop z rejestrów wyjściowych przy 104 Hz w zadaniu o
  wysokim priorytecie, a następnie uśrednia każdą parę do 52 Hz. Przekroczenie
  limitu czasu surowej ramki jest traktowane jako błąd zamiast cichego
  zaakceptowania luki.
- **Interpretacja:** uśrednianie dwóch pomiarów nie jest przypadkowym gubieniem
  co drugiej próbki. Jest świadomą filtracją i redukcją częstotliwości do
  docelowego strumienia 52 Hz.

### Kalibracja

- **Potwierdzone:** obie płytki mają oddzielne profile sześciopozycyjnej
  kalibracji. Korygowane są offset i skala akcelerometru oraz bias żyroskopu.
- **Wniosek:** zamiana płytki bez zmiany profilu może przesunąć rozkład cech.
  Stabilny prefiks urządzenia służy więc również do wyboru kalibracji.
- **Potwierdzone:** kalibracja jest stosowana w pipeline treningowym, a model
  wbudowany dla Green wykonuje tę samą korekcję przed obliczaniem cech. Model
  nie jest trenowany na danych skorygowanych, a następnie karmiony całkiem inną
  reprezentacją na urządzeniu.
- **Ograniczenie:** była to kalibracja inżynierska, bez wzorcowanego stanowiska i
  bez zapisanej temperatury otoczenia; nie powinna być opisywana jako
  kalibracja laboratoryjna wysokiej klasy.

### Mocowanie czujnika

- **Obserwacja użytkownika:** opaska na goleni miała tendencję do zsuwania się,
  a w jednym nagraniu obudowa została przypadkowo kopnięta i poluzowana około
  230. sekundy. Ten wcześniejszy plik testowy nie wszedł do finalnego datasetu.
- **Wniosek:** orientacja i sztywność mocowania są częścią warunków pomiaru.
  Zmiana kąta, strony goleni lub luzu może zmienić zarówno składowe osiowe, jak
  i charakter drgań.
- **Ograniczenie praktyczne:** prototypowa obudowa utrudniała idealnie
  powtarzalne mocowanie, a jej pokrywka została później zgubiona. Należy to
  podać jako ograniczenie konstrukcji prototypu, a nie ukrywać jako losowy
  problem użytkownika.

## 3. Dataset i przygotowanie danych

- **Potwierdzone:** dane obejmują jednego uczestnika, pięć klas oraz równoczesny
  pomiar z lewego nadgarstka i lewej goleni.
- **Potwierdzone:** surowe pliki pozostają niemodyfikowane. Korekty etykiet,
  brakująca metadana, krótkie ogony techniczne i wycięte przedziały są jawnie
  opisane w `dataset/curation/curation.json`.
- **Potwierdzone:** jedna para została omyłkowo nagrana z etykietą `walking`
  podczas biegu. Korektę przyjęto z zachowaniem informacji o pierwotnej nazwie,
  etykiecie, rozmiarze i CRC32.
- **Potwierdzone:** jazda na rowerze zawierała naturalne postoje, światła i
  przeprowadzanie roweru. Prawdopodobne przejścia zostały wykluczone jako jawne
  przedziały czasowe i przeniesione na oba urządzenia przez wspólny czas pary.
- **Wniosek:** „czystość” danych nie oznacza sztucznego braku naturalnych
  zdarzeń. Trzeba jednak zdecydować, czy celem etykiety jest cała sesja
  transportu, czy tylko aktywne pedałowanie. W obecnym eksperymencie modelowana
  jest aktywna czynność, dlatego postoje są wycinane.
- **Ograniczenie:** liczba niezależnych sesji jest znacznie mniejsza niż liczba
  okien. Szczególnie `cycling` ma tylko trzy niezależne sesje treningowe dla
  nogi, mimo setek zachodzących na siebie okien.
- **Wniosek:** zmiana osoby, roweru, nawierzchni, obuwia, prędkości lub sposobu
  mocowania może być ważniejsza niż dodanie kolejnych bardzo podobnych minut z
  tej samej sesji.

## 4. Ocena modeli i ryzyko zbyt optymistycznych wyników

- **Potwierdzone:** podział walidacyjny jest wykonywany po całych
  `paired_session_id`. Zachodzące na siebie okna z jednej sesji nie trafiają
  jednocześnie do treningu i testu, a nadgarstek i noga korzystają z tych samych
  foldów.
- **Potwierdzone:** dla modeli offline uzyskano:
  - nadgarstek: RF macro F1 0,855; SVM 0,874;
  - noga: RF macro F1 0,957; SVM 0,939.
- **Potwierdzone:** zamrożone wcześniej modele oceniono na później zebranym
  holdoucie — jednej nowej parze na klasę. Oba modele nogi poprawnie
  sklasyfikowały wszystkie 408 okien holdoutu.
- **Bardzo ważne ograniczenie:** 408 zachodzących na siebie okien nie jest 408
  niezależnymi eksperymentami. Holdout reprezentuje tylko pięć niezależnych
  sesji, tego samego uczestnika, z podobnego okresu i protokołu mocowania.
  Wyniku 100% nie wolno przedstawiać jako uniwersalnej skuteczności systemu.
- **Potwierdzone kontrole:** sprawdzono brak wspólnych hashy plików, identyfikatorów
  sesji i wektorów cech między treningiem i holdoutem. Kontrola z losowo
  permutowanymi etykietami grup dawała wyniki bliskie poziomowi losowemu.
- **Wniosek:** wysoka skuteczność może być prawdziwa dla wąsko zdefiniowanych
  warunków jednego użytkownika, a jednocześnie nie przenosić się na nowe
  warunki użytkowania.

## 5. Model wbudowany i test na żywo

### Implementacja

- **Potwierdzone (2026-08-23, wersja v1):** na Green wdrożono model Random Forest dla nogi:
  20 drzew, maksymalna głębokość 8, 36 cech typu średnia/odchylenie
  standardowe/minimum/maksimum, okno 260 próbek (5 s) i krok 130 próbek (2,5 s).
- **Potwierdzone:** eksport ma 892 węzły i około 25 910 bajtów tablic. Cały
  firmware wykorzystuje 24 488 z 237 568 bajtów RAM (10,3%) oraz 209 668 z
  811 008 bajtów flash (25,9%).
- **Potwierdzone:** predykcje eksportu float32 i modelu scikit-learn były zgodne
  dla wszystkich 4352 okien treningowych oraz 408 okien holdoutu — zero różnic
  klas. Testy natywne C++ przeszły 14/14 przypadków.
- **Potwierdzone:** pojedyncza predykcja na XIAO trwała około 62–63 ms. Zadanie
  zbierania IMU ma wyższy priorytet, a podczas krótkiej obserwacji nie wystąpił
  błąd próbkowania.
- **Zakres:** model jest celowo aktywny tylko na Green `18EE26A8`, ponieważ używa
  jej profilu kalibracji i został wytrenowany dla położenia na nodze. Blue nadal
  służy do rejestracji, ale nie wykonuje tego modelu.

### Pierwsza obserwacja poza warunkami datasetu

- **Obserwacja użytkownika (2026-08-23):** `sitting` i `lying` były na żywo
  rozpoznawane poprawnie, natomiast chodzenie w skarpetkach po mieszkaniu było
  wyświetlane jako `cycling`.
- **Różnica względem danych:** sesje chodzenia w datasecie były zbierane głównie
  w butach, na chodniku. Test w mieszkaniu oznaczał inne obuwie, nawierzchnię,
  prawdopodobnie krótsze odcinki, więcej zakrętów oraz inne tempo.
- **Hipoteza:** jest to przesunięcie domeny — model nauczył się również cech
  warunków rejestracji, a nie wyłącznie abstrakcyjnego wzorca chodu. Nie jest to
  jeszcze dowód, że przyczyną są konkretnie buty; kilka czynników zmieniło się
  jednocześnie.
- **Dlaczego to cenna obserwacja:** test na żywo ujawnił ograniczenie, którego
  nie pokazał mały holdout z podobnych warunków. Jest to dobry przykład różnicy
  między poprawnością implementacji modelu a zdolnością modelu do
  generalizacji.
- **Do sprawdzenia:**
  1. przetestować chodzenie w butach po chodniku, czyli w warunkach zbliżonych
     do treningowych;
  2. zachować oddzielną sesję chodzenia w skarpetkach w mieszkaniu jako dane
     diagnostyczne, bez natychmiastowego dodawania jej do treningu;
  3. porównać predykcje Python i firmware dla tej samej nowej sesji;
  4. jeśli celem ma być szersza odporność, zebrać kilka różnych sesji
     wewnątrz/na zewnątrz i pozostawić co najmniej jedną całą sesję jako nowy,
     nietknięty test.

### Test tempa i kierunku chodu na korytarzu

- **Obserwacja użytkownika (2026-08-23):** podczas próby na korytarzu w bloku
  model zachowywał się następująco:
  - wolny chód był przedstawiany jako `cycling`;
  - zwykłe tempo chodu było przedstawiane jako `walking`;
  - chodzenie w kółko w lewo było przedstawiane jako `cycling`;
  - chodzenie w kółko w prawo było przedstawiane jako `walking`, ale z niskim
    confidence;
  - zwykły bieg był przedstawiany jako `running`;
  - wolny bieg był przedstawiany jako `cycling`.
- **Interpretacja poparta strukturą modelu:** `cycling` zachowuje się jak klasa
  pośrednia dla ruchu o mniejszej intensywności niż typowy chód/bieg z danych.
  Obecny model wykorzystuje również statystyki oddzielnych osi. W finalnym
  lesie trzy cechy osi X akcelerometru (`mean`, `min`, `max`) odpowiadają łącznie
  za około 30% ważności cech. Zmiana kierunku zakrętu może więc zmieniać cechy
  osiowe na tyle, aby przekroczyć granicę decyzyjną.
- **Hipotezy wymagające danych surowych:**
  - zbiór ma za małą różnorodność tempa chodu i biegu;
  - długie łuki i kierunek skrętu są słabo reprezentowane;
  - model częściowo wykorzystuje orientację czujnika i kierunek przyspieszeń
    zamiast wyłącznie cech odpornych na obrót;
  - `cycling`, mające tylko trzy niezależne sesje treningowe, może zajmować zbyt
    szeroki obszar przestrzeni cech.
- **Dane wspierające hipotezę intensywności:** mediany cech okien treningowych
  dla nogi tworzą wyraźną skalę ruchu. Dla `cycling` mediana średniego modułu
  żyroskopu wynosiła 77,6 dps, dla `walking` 175,3 dps, a dla `running` 255,4
  dps. Mediana średniego dynamicznego modułu przyspieszenia wynosiła
  odpowiednio 0,335 g, 0,600 g i 1,256 g. Wolny chód lub trucht może więc trafić
  do obszaru amplitud reprezentowanego w treningu przez rower. Jest to dowód na
  podobieństwo cech zbioru, ale jeszcze nie pełne wyjaśnienie konkretnej
  predykcji na żywo.
- **Znaczenie dla pracy:** rozpoznawanie aktywności nie jest wyłącznie problemem
  pięciu nazw klas. Wewnątrz jednej klasy występują warianty tempa, trasy,
  obuwia i ruchu skrętnego. Walidacja z podobnych sesji może nie ujawnić tych
  wariantów.
- **Do sprawdzenia:** nagrać oddzielne, opisane sesje diagnostyczne: wolny chód
  po prostej, zwykły chód po prostej, łuki w lewo, łuki w prawo, wolny bieg i
  zwykły bieg. Najpierw wykorzystać je jako zewnętrzny test; dopiero później
  zdecydować, które mogą zasilić trening i zachować nowy, nietknięty holdout.
- **Analiza po obserwacji:** sprawdzono wariant ograniczający zależność od osi:
  12 statystyk modułów ruchu oraz tylko średnie trzech osi akcelerometru i
  żyroskopu (18 cech). W tej samej grupowej walidacji osiągnął macro F1 0,954 i
  balanced accuracy 0,959, wobec odpowiednio 0,956 i 0,961 dla obecnych 36
  cech. Usunięcie osiowych minimów, maksimów i odchyleń niemal nie pogorszyło
  wyniku offline, dlatego wariant 18-cechowy jest uzasadnionym kandydatem do
  osobnego testu A/B odporności na kierunek skrętu. Nie ma jeszcze dowodu, że
  naprawi wolny chód — do tego potrzebne są nowe surowe sesje lub powtórzony
  test na urządzeniu.
- **Potwierdzone surowymi danymi (2026-08-23):** sześć prób diagnostycznych z
  `dataset/raw/holdout/test` zawiera łącznie 10 113 próbek. Wszystkie pliki mają
  dokładnie 52,000 Hz, wyłącznie odstępy 19/20 ms, zgodny rozmiar, CRC32,
  etykietę oraz identyfikator Green. Analiza 68 zachodzących okien odtworzyła
  zachowanie widziane w aplikacji:
  - wolny chód: 16/16 okien jako `cycling`;
  - normalny chód: 8/8 jako `walking`;
  - kółka w lewo: 9/15 `cycling`, 6/15 `walking`;
  - kółka w prawo: 11/15 `walking`, 4/15 `cycling`;
  - wolny bieg: 4/6 `cycling`, 2/6 `walking`, bez `running`;
  - normalny bieg z zawrotem: 5/8 `running`, 3/8 `cycling`.
- **Potwierdzone:** wariant 18-cechowy ograniczył asymetrię skrętów (większość
  `walking` dla obu kierunków), ale nadal uznał wolny chód głównie za
  `cycling`, a wolny bieg za `walking`. Samo usunięcie osiowych ekstremów nie
  rozwiązuje problemu tempa.
- **Porównanie modeli:** pełny desktopowy SVM poprawnie rozpoznał 16/16 okien
  wolnego chodu prosto, lecz uznał wszystkie okna chodzenia po okręgu — w obu
  kierunkach — za `cycling`. Żaden z obecnie ocenionych modeli nie rozwiązuje
  wszystkich sześciu wariantów.
- **Wniosek metodologiczny:** te sześć sesji jest od teraz zbiorem
  diagnostycznym/deweloperskim. Jeżeli posłużą do wyboru cech lub modelu, nie
  mogą później pełnić roli końcowego, nietkniętego testu; trzeba nagrać osobne
  powtórzenie.
- **Przeszukanie kandydatów po diagnostyce:** porównano 45 konfiguracji Random
  Forest, obejmujących dziewięć zestawów cech i pięć ustawień wielkości drzewa.
  Każda konfiguracja zachowywała grupowy podział pierwotnych sesji, a sześć
  nowych plików służyło wyłącznie jako zbiór deweloperski.
- **Najlepszy kandydat bez dotrenowania nowymi plikami:** 27 cech łączących
  statystyki modułów, dominującą częstotliwość (kadencję), średnie i odchylenia
  osi; 20 drzew, głębokość 8 i `min_samples_leaf=2`. Uzyskał grouped-CV macro
  F1 0,956 oraz 55/68 poprawnych okien diagnostycznych wobec 30/68 obecnego
  modelu. Rozpoznał po 15/15 okien dla kółek w lewo i w prawo oraz 8/8
  normalnego chodu.
- **Pozostały problem:** dla wolnego chodu kandydat dał remis 8 `walking` / 8
  `cycling`, a wolny bieg rozdzielił na 3 `walking`, 2 `running` i 1
  `cycling`. Wniosek: kadencja i ograniczenie zależności osiowej mocno poprawia
  skręty, ale brak wariantów wolnego tempa w treningu nie może być w pełni
  naprawiony samym wyborem cech.

### Kandydat wbudowany v2 po danych diagnostycznych

- **Rozdzielenie eksperymentów:** pierwotne 98-cechowe RF i SVM pozostają
  zamrożonym porównaniem naukowym. Model wbudowany v2 jest osobnym eksperymentem
  inżynierskim, którego celem jest poprawa zachowania na żywo przy ograniczonej
  pamięci mikrokontrolera.
- **Implementacja v2:** 27 cech obejmuje średnią i odchylenie standardowe
  sześciu osi oraz średnią, odchylenie, minimum, maksimum i dominującą
  częstotliwość modułu przyspieszenia, dynamicznego modułu przyspieszenia
  `abs(|a|-1)` i modułu żyroskopu. Las ma 20 drzew, głębokość maksymalną 8,
  `min_samples_leaf=2` i 1312 węzłów.
- **Jawne wykorzystanie danych:** sześć plików diagnostycznych zostało dodanych
  do treningu finalnego kandydata v2. Od tej chwili są to dane rozwojowe, a nie
  holdout. Nie wolno cytować poprawności na nich po dotrenowaniu jako wyniku
  generalizacji.
- **Kontrola leave-one-file-out:** przy trenowaniu na pierwotnym zbiorze i
  pięciu z sześciu plików diagnostycznych uzyskano 49/68 poprawnych okien,
  macro F1 0,844 i poprawną większość sesji dla 5/6 plików. Jedyną całkowicie
  błędną sesją pozostał wolny chód, gdy jego jedyny plik był wyłączony z
  treningu (16/16 `cycling`). Pokazuje to wprost niedostatek niezależnych
  przykładów wolnego tempa.
- **Regresja na wcześniejszych danych:** grupowa walidacja pierwotnych sesji,
  z danymi diagnostycznymi dopuszczonymi tylko po stronie treningowej, dała
  macro F1 0,961. Na starym zamrożonym holdoucie v2 uzyskał macro F1 0,996 i
  poprawną większość wszystkich 5/5 sesji. Nie zaobserwowano więc istotnej
  utraty wcześniejszej jakości.
- **Zgodność implementacji:** scikit-learn i eksport float32 podały identyczne
  klasy dla 4352 okien pierwotnego zbioru, 408 okien starego holdoutu oraz 68
  okien diagnostycznych. Test natywny dodatkowo odtworzył 27 cech i predykcję
  dla 11 rzeczywistych okien surowego CSV. Łącznie przeszło 15/15 testów
  natywnych oraz 7/7 testów Pythona.
- **Budżet zasobów:** build v2 wykorzystuje 27 608/237 568 bajtów RAM (11,6%)
  oraz 225 380/811 008 bajtów flash (27,8%). Same tablice lasu zajmują
  szacunkowo 38 090 bajtów.
- **Pierwsza bramka sprzętowa (2026-08-23):** v2 wgrano na potwierdzoną po
  numerze seryjnym Green `18EE26A8`. Urządzenie uruchomiło protokół v6 w stanie
  `Idle`, a nieruchomą płytkę rozpoznawało jako `lying`. W dwóch obserwacjach
  trwających łącznie około 80 s zarejestrowano 33 predykcje. Obliczenie trwało
  65,4--67,4 ms i nie wystąpił `imu_sample_queue_overflow`, przekroczenie
  deadline ani inny zgłoszony błąd próbkowania.
- **Ograniczenie aplikacji podczas testu:** firmware oblicza predykcje również
  podczas zapisu CSV, ale kontroler i ekran `Data` nie prezentują telemetrii
  klasyfikatora dostępnej przez osobne połączenie `Home`. Dlatego skuteczność
  świeżych sesji jest oceniana offline z surowych CSV tym samym, sprawdzonym pod
  względem parity modelem v2. Nie jest to ograniczenie samego jednoczesnego
  logowania i inference na płytce.
- **Niewykonana jeszcze bramka:** krótka obserwacja nie zastępuje długiego testu
  loggera wraz z inference.
- **Świeży holdout v2:** po zamrożeniu modelu nagrano dziewięć nowych sesji:
  sitting, lying, wolny i normalny chód, kółka w lewo i prawo, wolny i normalny
  bieg oraz cycling. Przed predykcją zapisano SHA-256 surowych plików i nie
  zadeklarowano żadnych korekt ani wykluczeń.
- **Jakość nowych danych:** łącznie 32 131 próbek; każdy plik około 52 Hz,
  wyłącznie odstępy 19/20 ms, bez przerw powyżej 100 ms, identycznych kolejnych
  wektorów i clippingu.
- **Wynik v2:** 194/198 poprawnych okien, accuracy 0,980, balanced accuracy
  0,980 i macro F1 0,979. Wszystkie 9/9 sesji miało poprawną klasę
  większościową. Wolny chód oraz kółka w obie strony osiągnęły 100% okien
  `walking`, więc poprawa względem v1 ujawniła się również poza plikami użytymi
  rozwojowo.
- **Pozostała słabość:** 4/20 okien wolnego truchtu rozpoznano jako `cycling`,
  a średnia pewność tej sesji wynosiła tylko 49,7%. Normalny bieg miał 20/20
  poprawnych okien. Granica między wolnym biegiem i cycling nadal wymaga
  ostrożnej interpretacji.
- **Ograniczenie wyniku:** 198 okien zachodzi na siebie i pochodzi z dziewięciu
  sesji jednego uczestnika, zebranych tego samego dnia i w warunkach powiązanych
  z diagnostyką. Jest to uczciwy test post-freeze dla przyjętego protokołu, ale
  nie dowód generalizacji na inne osoby.

## 6. Wcześniejszy przebieg rozwoju — dodatkowe lekcje

### Odzyskanie projektu i zgodność wersji

- **Potwierdzone:** projekt był kontynuowany po formacie komputera na podstawie
  repozytorium oraz kopii niezacommitowanych zmian. Zamiast kopiowania całego
  backupu bez kontroli przeniesiono zmiany merytoryczne, zachowując aktualny
  Gradle Wrapper, konfigurację środowiska i pliki występujące tylko w repo.
- **Wniosek projektowy:** kopia katalogu roboczego nie zastępuje historii Git.
  Małe, sprawdzone punkty kontrolne oraz oddzielenie kodu, wygenerowanych
  artefaktów i surowych danych znacznie ułatwiają odtworzenie projektu.
- **Potwierdzone:** niezgodność aplikacji i firmware’u objawiała się m.in.
  komunikatami `Required BLEv3 characteristics not found` oraz później
  żądaniem instalacji zgodnego firmware’u v6. Po aktualizacji obu stron
  handshake dochodził do stanu `Ready`.
- **Wniosek:** wersja protokołu i lista capabilities muszą być sprawdzane przed
  dopuszczeniem komend. Czytelny błąd niezgodności jest bezpieczniejszy niż
  próba obsługi nieznanego zestawu charakterystyk.
- **Obserwacja użytkownika:** po prawie dwóch godzinach działania Green zaczęła
  zgłaszać w aplikacji potrzebę instalacji firmware’u v6 i nie łączyła się po
  zwykłym reconnect. Wcześniejsze zamknięte pliki pozostały zachowane, ale
  około 10 minut ostatniej jazdy nie zostało odzyskane.
- **Ograniczenie:** przyczyna tego pojedynczego zdarzenia nie została
  jednoznacznie zarejestrowana. Nie należy opisywać jej jako dowiedzionej
  utraty firmware’u; jest to nierozstrzygnięty przypadek wymagający logu
  szeregowego i testu resetu/zasilania.

### Błędy sterowania i interfejsu Android

- **Potwierdzone:** podczas jednego testu `Stop` zwrócił
  `command_write_not_started`. Plik został jednak później pobrany i przeszedł
  kontrolę CRC32. Pokazuje to, że błąd transportu odpowiedzi nie jest
  równoważny z niewykonaniem operacji przez urządzenie.
- **Wniosek projektowy:** po timeout lub utracie ACK aplikacja nie powtarza
  bezwarunkowo `Start`/`Stop`, lecz pyta o autorytatywny `status`. Identyfikatory
  żądań i idempotentne odpowiedzi ograniczają skutki starych callbacków GATT.
- **Potwierdzone:** początkowo licznik czasu sesji potrafił wizualnie
  przyspieszać, np. o dwie sekundy w ciągu jednej. Przyczyną było ponowne
  zakotwiczanie lokalnego zegara po okresowych aktualizacjach stanu. Po
  powiązaniu monotonicznego zegara UI z nazwą aktywnego pliku problem nie
  wystąpił w kolejnych próbach.
- **Wniosek:** czas prezentowany w UI i znaczniki czasu próbek są odrębnymi
  źródłami. Do analizy danych należy używać timestampów zapisanych przez
  firmware, a zegar aplikacji traktować jako informację dla użytkownika.
- **Potwierdzone rozwiązanie architektoniczne:** zwykła sesja Home/GPS oraz
  kontroler zbierania datasetu zostały rozdzielone. Przyciski Home nie sterują
  loggerem oznaczonych danych, co zmniejsza ryzyko przypadkowego rozpoczęcia lub
  zatrzymania zbioru treningowego.

### Pamięć QSPI i wpływ zapisu na próbkowanie

- **Potwierdzone:** na Green wystąpił `external_fs_mount_failed` mimo poprawnej
  inicjalizacji układu QSPI. Po jawnej zgodzie sformatowano wyłącznie pamięć tej
  płytki; normalny firmware uruchomił się potem z 2 039 808 wolnymi bajtami.
- **Wniosek:** formatowanie jest operacją serwisową i destrukcyjną. Osobny obraz
  formattera oraz sprawdzanie dokładnego identyfikatora urządzenia ograniczają
  ryzyko usunięcia danych z niewłaściwej płytki.
- **Potwierdzone:** okresowe `sync()` podczas aktywnego zapisu powodowało liczne
  przerwy 133–176 ms i efektywną częstotliwość tylko 38,173 Hz w
  `lying_29.csv`. Po usunięciu `sync()` z gorącej ścieżki `lying_30.csv`
  osiągnął 49,005 Hz bez przerw powyżej 100 ms.
- **Potwierdzone późniejsze rozwiązanie:** prealokacja segmentu i wydzielone
  zadanie IMU doprowadziły w dwóch pełnych segmentach do dokładnie 52,000 Hz;
  wszystkie 52 243 odstępy miały 19 albo 20 ms.
- **Wniosek:** bezpieczeństwo zapisu i regularność próbkowania są konkurującymi
  wymaganiami. Trwałą synchronizację wykonuje się przy finalizacji segmentu, a
  nie cyklicznie w krytycznej ścieżce pobierania IMU.

### Zasilanie

- **Obserwacja użytkownika:** aplikacja pokazała około 3900 mV / 84% dla jednej
  płytki i około 3600 mV / 24% dla drugiej. Telemetria pomogła podjąć decyzję o
  ładowaniu przed dłuższym zbieraniem.
- **Ograniczenie:** procent baterii jest estymacją z napięcia, a nie pomiarem
  rzeczywistej pojemności. Nie wykonano jeszcze kontrolowanego testu od pełnego
  naładowania do wyłączenia, dlatego nie należy deklarować dokładnego czasu
  pracy na baterii.
- **Obserwacja praktyczna:** brak widocznej diody ładowania przy zaklejonym
  prototypie utrudnia ocenę stanu. Telemetria napięcia jest użyteczna, ale nie
  zastępuje jednoznacznej sygnalizacji ładowania w finalnej obudowie.

### Decyzje dotyczące eksperymentu ML

- **Decyzja:** przy ograniczonym, jednoosobowym zbiorze zrezygnowano z małej
  sieci neuronowej jako głównego porównania. Zestawiono Random Forest i RBF SVM,
  ponieważ pozwalają uzyskać sensowny punkt odniesienia przy mniejszej liczbie
  danych i są łatwiejsze do przeanalizowania.
- **Wniosek:** wybór prostszego modelu nie usuwa problemu małej różnorodności.
  Ostatnie testy tempa i kierunku pokazują, że również Random Forest może
  dopasować się do warunków zbierania.
- **Ograniczenie ground truth:** etykiety pochodziły z ręcznie wybranej
  aktywności i notatek użytkownika, bez zewnętrznego systemu referencyjnego lub
  nagrania wideo. Naturalne przejścia trzeba było identyfikować na podstawie
  przebiegów i pamięci o przebiegu sesji.
- **Decyzja o klasach statycznych:** `sitting` i `lying` nagrywano w wielu
  naturalnych ułożeniach kończyn i na różnych meblach/pozycjach. Zmienność
  wewnątrz klasy jest pożądana, o ile podczas całej sesji rzeczywista klasa nie
  ulega zmianie.
- **Ograniczenie synchronizacji:** wspólny `paired_session_id` łączy pomiary
  ręki i nogi na poziomie sesji, ale zegary płytek nie są zsynchronizowane na
  poziomie pojedynczych próbek. Dane nadają się do porównania oddzielnych
  modeli położeń, lecz bez dodatkowej synchronizacji nie powinny służyć do
  ścisłej fuzji sensorów próbka-do-próbki.

### Ochrona surowych danych

- **Potwierdzone:** katalog `dataset/dataset_raw_own_backup` został porównany z
  `dataset/raw/own`; 178 odpowiadających plików było bitowo identycznych. Pełna
  kopia danych z telefonu jest dodatkowo przechowywana poza właściwym katalogiem
  treningowym.
- **Wniosek:** backup nie jest automatycznie datasetem treningowym. Do pipeline’u
  trafiają wyłącznie pliki dopuszczone przez reguły kuracji, a surowych kopii
  nie należy poprawiać w miejscu.

## 7. Tezy, które można bezpiecznie wykorzystać w pracy

- System realizuje odporny na utratę połączenia proces rejestracji,
  segmentacji, transferu, wznowienia oraz weryfikacji danych.
- Stabilna identyfikacja urządzeń umożliwia równoczesny eksperyment dwóch
  położeń i poprawne przypisanie kalibracji.
- Grupowanie podziału po sesjach ogranicza przeciek wynikający z nakładających
  się okien, ale nie zapewnia walidacji międzyosobniczej ani międzydomenowej.
- Dla badanego użytkownika i przyjętego protokołu noga dała lepsze wyniki niż
  nadgarstek, szczególnie dla rozróżnienia pozycji statycznych.
- Zgodność modelu desktopowego z eksportem nie gwarantuje skuteczności w nowych
  warunkach. Test mieszkanie/skarpety jest przykładem problemu generalizacji,
  nie błędu konwersji modelu.
- Negatywny wynik lub ograniczenie prototypu jest wartościowym rezultatem
  inżynierskim, jeżeli jest jawnie zmierzone, odtworzone i poprawnie opisane.

## 8. Otwarte testy

- dłuższy test loggera i inference v2 na Green z kontrolą ciągłości próbkowania;
- opcjonalne powtórzenie v2 innego dnia lub na innej osobie, bez dotrenowania;
- dłuższa jednoczesna próba loggera i inference, w tym rotacja segmentu;
- zachowanie klasyfikatora po reconnect i przy zablokowanym ekranie;
- test przerwania zasilania, celowo błędnego CRC i braku miejsca;
- ocena na innej osobie — obecnie niemożliwa, dlatego pozostaje ograniczeniem
  pracy, a nie spełnionym kryterium.

## 9. Liczenie kroków

- **Implementacja kandydata:** dla Green zastosowano moduł skalibrowanego
  żyroskopu, filtr EMA `alpha=0,35`, próg lokalnego maksimum 80 dps i okres
  refrakcji 400 ms. W tym mocowaniu dwa piki modułu przypadają na pełny cykl
  lewej nogi, dlatego pojedynczy zaakceptowany pik jest traktowany jako jeden
  krok całego ciała i nie jest mnożony przez dwa.
- **Ochrona przed rowerem:** sama periodyczność nie rozróżnia chodu od
  pedałowania. Kandydaci są buforowani, a po 2,5 s zatwierdzani wyłącznie przez
  okno klasyfikatora `walking` lub `running`. Pierwsze zdarzenia nie giną podczas
  oczekiwania na pięciosekundowe okno modelu.
- **Preflight bez ground truth:** na dziewięciu świeżych plikach sitting, lying
  i cycling dały po 0 kroków. Kadencje wyniosły około 71--98 kroków/min dla
  wariantów chodu i 134 kroków/min dla normalnego biegu. Wolny trucht dał około
  108 kroków/min, ponieważ cztery okna błędnie sklasyfikowane jako cycling
  odrzuciły część kandydatów.
- **Bardzo ważne ograniczenie:** te liczby są testem sensowności, a nie
  dokładnością. Pliki nie mają ręcznie policzonej liczby kroków. Wymagane są
  próby po dokładnie 100 kroków, bez strojenia progów między powtórzeniami.
- **Android:** licznik firmware’u narasta od uruchomienia Green, natomiast
  aplikacja zapamiętuje stan w chwili rozpoczęcia sesji Home i wyświetla różnicę.
  Spadek licznika urządzenia jest interpretowany jako restart płytki.
- **Koszt:** licznik zwiększył użycie do 27 776 B RAM (11,7%) i 226 676 B flash
  (27,9%). Pełny zestaw natywny przeszedł 19/19 testów, a testy Androida i build
  APK zakończyły się poprawnie.
- **Walidacja fizyczna licznika:** przy zamrożonych progach wykonano osiem prób
  po 100 ręcznie liczonych kroków. Dla zwykłego chodu uzyskano 96, 99 i 95
  kroków (średni błąd bezwzględny 3,33%), dla wolnego chodu 112, 110 i 105
  (nadliczanie średnio o 9,0%), a dla biegu 88 i 90 (niedoliczanie średnio o
  11,0%). Łącznie wykryto 795 z 800 kroków, lecz mały błąd sumaryczny -0,625%
  wynika z wzajemnego znoszenia błędów; średni błąd bezwzględny pojedynczej
  próby wyniósł 7,375 kroku. Podczas 3 minut siedzenia, 3 minut leżenia i 8
  minut jazdy na rowerze nie zarejestrowano żadnych fałszywych kroków.
- **Wniosek z walidacji kroków:** błąd zależy od sposobu poruszania się i ma
  przeciwny znak dla wolnego chodu oraz biegu, więc jedna globalna poprawka
  skalująca nie rozwiązałaby problemu. Jest to ważne ograniczenie prostego
  algorytmu progowego i dobry punkt odniesienia do omówienia w pracy.

## 10. Czas aktywności i szacowanie kalorii

- Interfejs rozróżnia `Current activity time`, czyli czas nieprzerwanego
  rozpoznawania bieżącej klasy mierzony przez firmware, oraz `Session`, czyli
  całkowity czas sesji Home mierzony przez telefon.
- Czas sesji i całkowanie kalorii korzystają z monotonicznego zegara Androida
  (`elapsedRealtime`), dlatego zmiana godziny systemowej nie może spowodować
  skoku wyniku. Po zatrzymaniu sesji obie wartości przestają rosnąć.
- Energia jest przybliżeniem brutto ze wzoru
  `kcal = MET * 3,5 * masa_kg / 200 * czas_min`. Przyjęto stałe MET: lying 1,0,
  sitting 1,3, walking 3,5, cycling 6,8 i running 8,0; `unknown` nie nalicza
  energii. Masa pochodzi z ustawień aplikacji.
- Nie jest to pomiar metaboliczny: wynik nie uwzględnia tętna, prędkości,
  nachylenia, indywidualnej sprawności ani intensywności wewnątrz jednej klasy.
  W pracy należy nazywać go szacunkową liczbą kalorii.
- Walidację wykonano bez kolejnego wysiłku fizycznego. Test z wirtualnym zegarem
  zasymulował 10 s chodzenia i 20 s biegu, sprawdził rozdzielenie składników
  energii oraz zatrzymanie naliczania po `Stop`. Sprawdzono także wartości dla
  wszystkich pięciu klas i błędne dane wejściowe. Cały zestaw Androida przeszedł
  43/43 testy, a `assembleDebug` zakończył się poprawnie.

## 11. Powiązane źródła dowodowe

- `docs/hardware_validation_v5.md` — testy IMU, segmentacji i zablokowanego
  ekranu;
- `docs/hardware_validation_v6.md` — dwie płytki i równoległe nagrywanie;
- `docs/ml_pipeline.md` — pipeline, podział grupowy i wyniki modeli;
- `dataset/curation/curation.json` — jawne decyzje dotyczące danych;
- `dataset/results/metrics.json` — pełne metryki i macierze pomyłek;
- `dataset/results/embedded_model_selection.json` — wybór małego modelu;
- `dataset/results/embedded_export.json` — zgodność eksportu z Pythonem.
- `dataset/results/embedded_v2/` — ocena inżynierska kandydata v2;
- `dataset/curation/live_diagnostic_2026-08-23.json` — pochodzenie sześciu
  sesji diagnostycznych użytych rozwojowo.
- `docs/embedded_v2_fresh_test.md` — protokół świeżego testu v2;
- `dataset/results/embedded_v2_fresh_holdout_2026-08-23/` — wynik końcowego
  holdoutu v2.
- `docs/step_counter.md` — algorytm, preflight i protokół walidacji kroków.

## 12. Trwałe sesje Home i końcowe wnioski implementacyjne

- Home używa wyłącznie zapisanego adresu Green `18EE26A8`. Rozdzielenie
  urządzenia użytkowego od Blue usuwa niedeterministyczny wybór pierwszej
  reklamy BLE i pozwala powiązać wyniki z właściwą kalibracją oraz modelem.
- Czas nie jest przepisywany z chwilowej etykiety po rozłączeniu. Interwał jest
  zaliczany do klasy tylko przy aktywnym BLE i telemetrii nie starszej niż 3 s;
  pozostały czas trafia do `unknown` i nie nalicza kalorii. Dzięki temu brak
  danych nie wygląda w historii jak pewna predykcja.
- Masa użytkownika jest zamrażana przy Start. Zmiana ustawień w trakcie lub po
  sesji nie zmienia jej historycznych kalorii. `MET_v1` jest zapisywane jawnie,
  co umożliwia odtworzenie metody obliczeń.
- GPS jest dodatkiem, a nie warunkiem pomiaru. Odmowa lokalizacji daje poprawną
  sesję bez punktów trasy oraz eksport CSV zawierający sam nagłówek.
- Checkpoint SQLite co 5 s ogranicza stratę po zabiciu procesu. Rekord `Active`
  po ponownym uruchomieniu staje się `Interrupted` dokładnie na ostatnim
  checkpointcie; system nie dopisuje czasu, którego rzeczywiście nie obserwował.
- Kopia SQLite jest źródłem prawdy, a SAF jest eksportem. Utrata uprawnienia do
  folderu nie usuwa historii i może być naprawiona późniejszym Retry.
- Testy UI początkowo przechodziły dopiero po ręcznym odblokowaniu telefonu.
  Logcat pokazał, że zwykła aktywność testowa przechodziła natychmiast z
  `RESUMED` do `PAUSED` przez keyguard. Debugowy host `showWhenLocked` usunął tę
  zależność i pozwolił przejść 7/7 testów także przy aktywnym lockscreenie. Nie
  należy jednak mylić tego z walidacją ciągłości prawdziwego BLE w tle — to
  osobny test usługi pierwszoplanowej.

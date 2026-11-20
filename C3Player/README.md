# C3 Player — Android project (Now Playing + Media3 integration)

Proiect Kotlin + Jetpack Compose, cu Media3 ExoPlayer și interfață originală dark/lime.

## Implementat în această versiune
- Ecran Now Playing conectat direct la instanța Media3 ExoPlayer.
- Sincronizare a stării de redare, buffering-ului, duratei și poziției (actualizare la 250 ms).
- Seek pe bara de progres; salt la piesa anterioară/următoare.
- Play/pause, shuffle și ciclu repeat off → all → one.
- Mini-player sincronizat cu poziția și coperta piesei.
- Citirea metadatelor locale și încercarea încărcării coperții albumului din MediaStore.
- Vizualizator cu bare animate. Este o animație vizuală decorativă, nu o analiză FFT a semnalului audio.

## Deschidere și rulare
1. Deschide folderul în Android Studio.
2. Lasă Gradle să sincronizeze dependențele.
3. Rulează configurația `app` pe un dispozitiv sau emulator.
4. Acordă permisiunea pentru accesul la fișiere audio.

Proiectul folosește compileSdk/targetSdk 35 și minSdk 26 în configurația curentă. Ajustează SDK-urile la versiunea instalată și cerințele de publicare înainte de lansare.

## Limitări rămase (nu sunt prezentate ca implementate)
- Playerul este încă legat de lifecycle-ul Activity; redarea garantată în fundal, MediaSessionService, notificarea media și comenzile pe ecranul blocat nu sunt încă integrate.
- Egalizatorul, Bass Boost, crossfade, ReplayGain, sleep timer, playlist-urile persistente, versurile și editarea metadatelor nu sunt implementate.
- Coperta depinde de metadatele furnizate de MediaStore; unele fișiere nu au copertă sau pot să nu o expună.
- Spectrul este animat decorativ, nu măsoară amplitudinea reală a frecvențelor.

## Verificare
Codul a fost actualizat la nivel de surse. Nu a fost compilat și testat pe un dispozitiv Android în acest mediu; verifică sincronizarea Gradle și build-ul în Android Studio.

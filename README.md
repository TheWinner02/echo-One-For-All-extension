# One For All

Estensione musicale autonoma per Echo. Aprire questa cartella come progetto Android Studio separato: non è un modulo di Echo e non richiede Combine o le altre estensioni installate.

## Prima implementazione: punti 1–4

- `ext`: ingresso pubblico `dev.brahmkshatriya.echo.extension.OneForAllExtension`, configurazione, account e fallback.
- `engine`: coordinatore derivato da Combine; catalogo, ricerca, libreria, playlist e matching delle registrazioni.
- `providers/*`: sei moduli isolati, con namespace distinti, per Spotify, Deezer, YouTube Music, SoundCloud, Qobuz e Tidal.
- `app`: contenitore Android dell'estensione, identificativo `one_for_all`; non è un'app musicale alternativa a Echo.

Il catalogo predefinito è Spotify; Deezer e YouTube Music sono le sorgenti audio iniziali. SoundCloud, Qobuz e Tidal sono inclusi ma vanno abilitati nelle impostazioni. Il servizio scelto come catalogo rimane disponibile anche se il suo interruttore è disattivato. Le preferenze di sorgente consentono di cambiare la priorità.

## Account

Accedere da Account di One For All in Echo e scegliere il servizio. I moduli con accesso browser espongono un modulo senza campi: il pulsante di accesso apre il normale browser integrato di Echo. Gli altri mantengono i moduli di accesso nativi. È possibile aggiungere più servizi allo stesso profilo One For All; questa prima versione conserva un account per servizio.

Le impostazioni e le sessioni sono separate con il prefisso `provider.<servizio>.`. Le credenziali delle estensioni già installate non vengono importate. Il profilo aggregato restituito a Echo non contiene token dei servizi; i dati di sessione necessari ai moduli sono conservati nelle preferenze private dell'estensione tramite lo SDK. Non vengono introdotti log delle credenziali. La modalità non autenticata disattiva gli account salvati; la voce Disconnect del servizio li rimuove.

## Riproduzione e fallback

Il matching cerca la stessa registrazione tramite metadati, artista, durata e ISRC quando disponibile, preservando le scelte esatte memorizzate da Echo. Le ricerche avvengono con concorrenza massima 2 e limite per servizio. Il caricamento del brano ha un limite complessivo di 40 secondi.

Con Automatic source fallback attivo, un errore o un server vuoto nel resolver fa provare altri formati e poi altri servizi già associati al brano. Ogni servizio ha 6 secondi per risolvere l'audio. I servizi che superano il tempo vengono temporaneamente evitati come alternative per 30 secondi; una registrazione mancante non causa il blocco globale del servizio. Il servizio selezionato viene comunque ritentato. Retry unavailable services azzera il cooldown.

I descrittori di fallback sono serializzabili, limitati a 24 candidati e 64 KiB: il ripristino della coda non dipende da una mappa di oggetti in memoria. Le scelte esatte salvate non ricevono alternative automatiche. I titoli delle sorgenti risolte indicano il servizio effettivo.

Gli errori avvenuti *dopo* la risoluzione, durante la lettura dell'URL da parte del player, sono gestiti dal fallback del player di Echo Fluid aggiornato. L'estensione da sola non riceve gli errori di ExoPlayer attraverso lo SDK e non può garantire quel recupero su host precedenti. Non viene promesso audio lossless per un file privo di qualità verificabile, né disponibilità di servizi/backend esterni.

## Build manuale

Usare JDK 21 e SDK Android 37. Il progetto ha un wrapper Gradle indipendente e versioni esplicite; non richiede un repository Git inizializzato.

```powershell
.\gradlew.bat :app:exportExtension
```

Il file per l'importazione da Echo viene copiato in `release/one_for_all-v0.1.0.eapk`. Per il formato JVM:

```powershell
.\gradlew.bat :ext:shadowJar
```

Il contenitore release usa la configurazione di firma del progetto: non è stato aggiunto alcun keystore personale. L'importazione del file estensione è distinta dall'installazione Android tramite Package Installer. Repository e URL di aggiornamento sono vuoti finché il progetto non viene pubblicato.

## Verifiche e limiti

I sorgenti di produzione e i protobuf sono stati controllati con i compilatori locali contro lo SDK pubblico di Echo. I test in `ext/src/test` coprono isolamento delle preferenze, account e ripristino sessione, inizializzazione lazy, fallback, formati alternativi, timeout, cancellazione e ripristino dei descrittori. Non sono state eseguite build Gradle, generazioni APK, installazioni o prove di riproduzione con account reali.

I moduli mantengono le capacità e i limiti dei progetti di origine; l'inclusione di Qobuz/Tidal non certifica il funzionamento dei rispettivi backend. Le nuove etichette del coordinatore sono al momento in inglese; le traduzioni native dei provider sono mantenute. Navidrome, Jellyfin, Plex e gli altri servizi proposti sono fuori dai punti 1–4 e non vengono presentati come implementati.

## Icona e provenienza

`icon.png` è il master: nucleo luminoso e bagliori multicolore ispirati al potere One For All, senza copiare personaggi o il marchio della serie. `assets/ICON-PROMPT.txt` conserva la descrizione della generazione. Una miniatura PNG incorporata nei metadati rende l'icona disponibile senza pubblicarla su un server.

`PROVENANCE.json` registra i progetti e i commit sorgente; `licenses/` conserva i file di licenza presenti nelle copie originali. Gli originali di Echo, Combine e dei provider non sono stati modificati da questa implementazione.


## Revisione accessi — 24 novembre 2026

Percorsi esposti: Spotify tramite browser o cookie (sp_dc); YouTube Music e SoundCloud tramite browser; Deezer tramite browser, email/password o ARL; Qobuz tramite email/password o token. Tidal mantiene la propria verifica di sessione nelle impostazioni del servizio.

I login browser hanno una finestra di 10 minuti, separata dai timeout dei resolver audio; la risoluzione finale dell'account ha un limite di 30 secondi. I moduli credenziali hanno un limite di 60 secondi. Campi e moduli sono identificati per servizio e modalità, così gli input conservati da Echo non vengono inoltrati al servizio sbagliato.

Ogni tentativo usa un client e preferenze temporanei; le modifiche si salvano solo dopo una risposta valida. Errori, annullamenti e account vuoti non sostituiscono la sessione funzionante. Un accesso riuscito abilita il servizio. Lo stato dell'account è visibile nelle relative impostazioni; Disconnect agisce su quel servizio. I token non sono più esposti come campi di testo nelle impostazioni generiche.

Deezer usa cookie ARL/SID per il browser, senza richiedere l'intercettazione del corpo della password, e mantiene sessioni separate per istanza. Gli ARL vuoti e gli utenti ospiti vengono rifiutati. Qobuz valida anche il token ottenuto da email/password contro il profilo remoto prima di salvarlo. YouTube rigenera l'header SAPISIDHASH al ripristino della sessione. I redirect browser vengono riconosciuti sull'origine prevista.

Verifica locale: 36 test superati e compilazione isolata dei sorgenti contro lo SDK pubblico. I test comprendono risposte HTTP simulate per Qobuz, callback browser, campi separati, errori, cancellazione, disconnessione e ripristino. Non costituiscono una prova dei login con account reali né delle richieste di autenticazione a due fattori. Nessuna build Gradle o installazione è stata eseguita dall'agente durante questa revisione.


## Prova browser Spotify con Echo aggiornato

Il provider Spotify incorporato ora richiede il profilo di browser `spotify-login-v1` attraverso il segnale interno `X-Echo-WebView-Profile`. Echo rimuove questo segnale prima delle richieste HTTP e attiva il percorso dedicato soltanto per l'URL di login HTTPS di Spotify. Il protocollo non richiede modifiche allo SDK pubblicato; per usare tutte le correzioni occorre aggiornare sia Echo Fluid sia questa estensione.

Il profilo mantiene lo user agent Chrome 131 mobile già utilizzato. La pagina iniziale ritorna esplicitamente a `open.spotify.com`. Echo attende il completamento della cancellazione dei cookie prima di caricare la pagina, supporta i popup di autenticazione e i cookie di terze parti, poi combina i cookie dei tre domini Spotify quando compare una sessione `sp_dc`. Il profilo remoto continua a essere validato dall'estensione prima di salvare l'account. La diagnostica del browser registra soltanto il dominio e lo stato HTTP, senza URL completi, dati dei moduli, header o cookie.

Verifica locale: compilazione isolata riuscita; 37 test dell'estensione e 12 test del browser Echo superati. La prova di autenticazione con un account reale è ancora da effettuare sul telefono. Non è stato modificato il progetto dell'estensione Spotify originale. Non sono state eseguite build Gradle o installazioni dall'agente.


## Recupero della sessione SoundCloud

Il browser SoundCloud di One For All richiede ora il profilo `soundcloud-login-v1` di Echo. Occorre aggiornare entrambi i progetti; l'estensione SoundCloud separata e gli altri progetti non vengono modificati.

Il profilo riconosce anche le pagine personali e la navigazione SPA, non solo gli URL feed/discover. Durante il login verifica i cookie delle origini SoundCloud, le sole chiavi di sessione note `oauth_token`/`auth_token` del sito e le richieste autenticate `/me` verso i due host API SoundCloud. Le richieste del profilo possono fornire anche il client_id pubblico corrente. Non vengono raccolti dati dei moduli, password o corpi delle richieste di autenticazione; nessun valore di sessione viene stampato nei log.

Il browser rimane aperto finché il provider non verifica un account reale. Token anonimi/rifiutati non creano profili fittizi e non chiudono prematuramente il login. Il provider prova il proprio endpoint web e, se la sessione è rifiutata, quello pubblico `/me`; gli errori HTTP 429 non provocano tentativi immediati sul secondo endpoint. Token e client pubblico verificati vengono salvati attraverso la transazione di accesso esistente. L'accesso manuale tramite OAuth token è mantenuto.

Riferimento per la verifica del profilo: https://developers.soundcloud.com/docs/api/guide#accessing-resources . Il flusso qui mantiene la sessione del browser preesistente; non viene presentato come una nuova applicazione OAuth registrata.

Verifica locale: compilazione isolata riuscita, 50 test dell'estensione e 21 test del browser superati. Include cookie alternativi/codificati, sessioni rifiutate, client pubblico, account vuoti, disconnessione, navigazione SPA e regressioni degli altri provider. La conferma con l'account reale sul telefono resta da effettuare. Nessuna build Gradle o installazione è stata eseguita dall'agente.


### Correzione verificata sul percorso mobile

La diagnosi ADB sulla WebView reale ha rilevato `m.soundcloud.com`, con `oauth_token` limitato al dominio mobile, e richieste profilo a `api-mobi.soundcloud.com`. Il precedente elenco dei domini non includeva questi due host e impediva il rilevamento, pur con accesso completato nel sito.

Il browser include ora il dominio mobile tra le origini dei cookie e conserva l'origine mobile nel passaggio al provider. Il provider può verificare il profilo sul servizio mobile e ripiegare sugli endpoint esistenti se quello mobile è assente o rifiuta l'accesso. La scoperta tardiva del client_id consente di ritentare la stessa sessione, senza bloccarla per il primo tentativo con un client precedente. Sono supportati anche cookie con virgolette codificate.

Esito locale: 52 test dell'estensione e 23 test del browser. La presenza del cookie e l'origine mobile sono state verificate sul dispositivo; la chiusura automatica completa con questa nuova build deve ancora essere confermata dopo l'aggiornamento di Echo e One For All. Nessun valore di sessione è stato riportato o salvato negli artefatti diagnostici.

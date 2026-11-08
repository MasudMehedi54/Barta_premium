# Barta v3 - real-time chat + voice/video calls (Java, Android Studio, 100% free)

Barta lets people register with email, find other users, and chat one-to-one or in groups
with text, photos and locations. Version 2 adds the **Firebase Realtime Database** (online / last seen,
typing, seen ticks, unread dots) and a set of WhatsApp-style features. Everything runs on the
**Firebase Spark (free) plan** - no credit card, no Blaze, no Cloud Functions, no paid APIs.

## What is new in v3: voice and video calls
| Part | How it works | Free? |
|---|---|---|
| Call buttons | 📞 and 🎥 in the top bar of a 1-to-1 chat | - |
| The call itself | **WebRTC** (`io.getstream:stream-webrtc-android`, open source). Voice/video goes **directly phone-to-phone** | Yes |
| Setting up the call ("signalling") | Realtime Database `calls/` and `callData/` carry the short offer / answer / network-address texts | Yes (RTDB on Spark) |
| Finding each other on the internet | Google's public STUN servers | Yes |
| Ringing | Background service listens to `calls/{myUid}`: opens the call screen, or shows a ringing notification with Answer / Decline | Yes |
| Call in the background | `CallService` (foreground service, type microphone/camera) keeps the call alive when you press Home | On-device |
| Call history | After each call a line appears in the chat: "📞 Voice call · 2:31", "📹 Missed video call" | Yes (Firestore) |
| Busy | If the person is already in a call, the caller sees "... is on another call" | Yes |

### How a call works (simple)
1. A taps 📞. A's phone writes `calls/{B}` = "A is calling you" and `callData/{id}` with an **offer**.
2. B's phone sees it and rings. B taps **Answer**, and B's phone writes an **answer**.
3. Both phones write their network addresses (ICE candidates) under `callData/{id}`.
4. WebRTC connects the two phones **directly**; Firebase is no longer used for the call.
5. Either taps the red button: status becomes `ended`, the texts are deleted, A writes the call line into the chat.

### Calling limits (because it is free)
| Situation | Works? |
|---|---|
| Both phones on Wi-Fi | Usually yes |
| One or both on mobile data | Sometimes not, unless you add a free TURN server (below) |
| Barta fully closed / swiped away on B's phone | B's phone will not ring (waking a closed app needs a paid push server) |
| Group calls | Not supported (needs a paid media server) |

### Optional: make calls work on mobile data (free TURN)
1. Open https://www.metered.ca/tools/openrelay/ and create a free account / app.
2. Copy the TURN URLs, username and password it shows you.
3. Paste them into `app/src/main/java/com/barta/app/call/CallConfig.java` (`TURN_URLS`, `TURN_USERNAME`, `TURN_PASSWORD`) and rebuild.
   The free plan has a monthly data limit: fine for testing and your demo.

## What was new in v2
| Feature | How it works | Free? |
|---|---|---|
| Online / last seen | Realtime Database `status/{uid}` + `onDisconnect()` (the server marks you offline if the phone dies) | Yes (RTDB on Spark) |
| "typing..." | Realtime Database `typing/{chatId}/{uid}`, cleared after 3 s idle and on disconnect | Yes |
| Seen ticks (double tick in brand colour) | Realtime Database `reads/{chatId}/{uid}`; groups show "seen" only when everyone read it | Yes |
| Unread dots + "Unread" filter | Realtime Database `userReads/{uid}/{chatId}` | Yes |
| Reply, edit, delete for everyone | Long-press a message (or swipe it right to reply). Quote bubbles, "edited" label, "This message was deleted" placeholder | Yes (Firestore) |
| Reactions | Long-press, pick an emoji. Tap the same emoji to remove it | Yes (Firestore) |
| Search inside a chat | Menu > Search. Searches the Room cache, so it works offline. Up/down arrows jump between matches | On-device |
| Profile photo + "about" | You tab. Photo is a 256 px Base64 JPEG in `users/{uid}` (same trick as chat photos) | Yes (Firestore) |
| Dark mode | You tab > Theme: System / Light / Dark (`values-night/colors.xml`, DayNight theme) | On-device |
| Scheduled messages | Type a message, **long-press the send button**, pick date + time. Uses WorkManager. Menu > Scheduled messages to cancel | On-device |
| Block and mute | Chat menu. Stored as `blocked` / `muted` lists in your Firestore user document. Blocking is enforced by the security rules | Yes (Firestore) |
| Share location | Attach (paperclip) > Location. Sends coordinates; tap opens any map app with a `geo:` link (no Google Maps API key) | On-device |

## Free-tier check (verified November 2026 on firebase.google.com/pricing)
| Service | Spark plan | Used by Barta? |
|---|---|---|
| Authentication (email/password) | Free | Yes |
| Cloud Firestore | Free: 1 GiB stored, 50K reads / 20K writes / 20K deletes per day | Yes |
| Realtime Database | Free: 1 GB stored, 10 GB/month download, 100 simultaneous connections, 1 database per project | Yes (new) |
| Cloud Messaging (FCM) | Free, but *sending* needs a server or Cloud Functions | No - a foreground service shows local notifications instead |
| Cloud Storage | Needs the Blaze plan since February 2026 | No - images are Base64 in Firestore |
| Cloud Functions | Blaze only | No |
| Google Maps SDK | Needs an API key with billing | No - `geo:` links instead |

## 1. Open the project
1. Android Studio (Koala 2024.1+ recommended, bundled JDK 17) -> **File > Open** -> select the `Barta` folder.
2. Let Gradle sync. If asked to upgrade the Android Gradle Plugin, you can decline.

## 2. Firebase setup (do ALL steps, in this order)
1. https://console.firebase.google.com -> your `Barta` project (or **Add project**, Analytics off).
   Stay on **Spark**. Never click "Upgrade".
2. If the Android app is not added yet: **Add app -> Android**, package name exactly `com.barta.app`.
3. **Build > Authentication > Get started > Sign-in method > Email/Password > Enable.**
4. **Build > Firestore Database > Create database** -> location -> **Production mode**.
   Open the **Rules** tab, paste `firestore.rules` from this folder, **Publish**.
5. **Build > Realtime Database > Create Database** -> pick a location (for Bangladesh,
   *Singapore (asia-southeast1)* is closest) -> **Start in locked mode** -> Enable.
   Open its **Rules** tab, paste `database.rules.json` from this folder, **Publish**.
   **v3:** if you already did this for v2, paste the NEW `database.rules.json` again (it adds `calls` and `callData`).
6. **Download `google-services.json` again** (Project settings > Your apps > google-services.json)
   and replace the old one in the **`app/`** folder.
   This step matters: the new file contains `"firebase_url"` (your Realtime Database address).
   An old file without it causes the crash *"Failed to get FirebaseDatabase instance: Specify DatabaseURL"*,
   which is the usual reason "the Realtime Database doesn't work".
7. Android Studio: **Sync Project with Gradle Files**, then Run on a device/emulator (API 24+).
   The offline cache upgrades itself (Room v1 -> v2) and simply re-downloads your messages.

## 3. Test it (two phones or phone + emulator)
1. Register two accounts. On phone A open a chat with B.
2. **Online / typing**: open the same chat on B. A's header shows "online"; type on B and A shows "typing...".
   Close the app on B: A shows "last seen today at ...".
3. **Seen**: send from A; the tick turns into the brand-coloured double tick when B opens the chat.
4. **Reply / react / edit / delete**: long-press a message. Swipe a message right to reply.
5. **Search**: chat menu > Search, type a word, use the arrows.
6. **Location**: paperclip > Location, allow permission, Send. Tap the card to open your map app.
   (On an emulator, set a location in Extended controls > Location first.)
7. **Schedule**: type a message, long-press send, choose a time 2-3 minutes ahead. Menu > Scheduled messages lists it.
8. **Profile / dark mode**: You tab > tap the photo, Name, About, Theme.
9. **Block / mute**: chat menu. A blocked person can't send to you (the server rejects it) and doesn't see your online status.

### Test calls
1. Two phones (or phone + emulator with camera), both **on Wi-Fi**, both signed in with different accounts, Barta open on both.
2. On phone A open the chat with B, tap 📞 (voice) or 🎥 (video). Allow microphone / camera.
3. Phone B rings: tap **Answer**. Try Mute, Speaker, Camera off, Flip camera.
4. Press Home during the call: it keeps going ("Ongoing call" notification; tap it to return).
5. End the call: the chat shows "📞 Voice call · 0:42". Call again and press Decline / don't answer to see "Missed".
6. If B is in Barta's background, B gets a ringing notification with **Answer / Decline**
   (Android 13+: allow notifications; Android 14: Settings > Apps > Barta > "Full screen notifications" for the big call screen).

## Syllabus map
| Course topic | Where in Barta |
|---|---|
| Activities, lifecycle, Intents | All `ui/*Activity`. Implicit intents: gallery (`GetContent`), maps (`geo:` in `LocationHelper.open`). Lifecycle: `ChatActivity.onStart/onStop` (typing listener, read receipts), `BartaApp` + `ProcessLifecycleOwner` (online/offline) |
| Gradle, AndroidManifest | `app/build.gradle`, `AndroidManifest.xml` (location + notification permissions, Application class, service type) |
| RecyclerView, adapters, view holders | `MessageAdapter` (2 view types, quotes, reactions, highlight), `ItemTouchHelper` swipe-to-reply in `ChatActivity.setupList`, `ChatAdapter`, `UserAdapter` |
| Custom Views, Material Design, themes | `view/AvatarView` (round photo via `BitmapShader`, canvas-drawn online dot), BottomSheetDialog, chips, **DayNight theme + `values-night`** |
| Services | `service/MessageListenerService` (foreground service + local notifications, incoming calls), `service/CallService` (foreground service type microphone/camera) |
| Multimedia (audio, camera) | `call/WebRtcClient` (camera capture, microphone, video rendering with `SurfaceViewRenderer`), `call/CallAudio` (ringtone, ring-back tone, speaker/earpiece with `AudioManager`) |
| Background work | `work/ScheduledMessageWorker` (WorkManager, network constraint, retry) |
| Broadcast Receivers | `receiver/NetworkReceiver` (offline banner), `receiver/CallActionReceiver` (Decline button in the call notification) |
| Content Providers + permissions | `UsersFragment.readContacts()` (Contacts provider), runtime permissions for contacts, location, notifications |
| Networking, JSON | `util/QuoteFetcher` (HttpURLConnection + org.json), `MessageMapper` (reactions as JSON in Room) |
| Database manipulation (SQLite/Room) | `db/*`: message cache, `search()` with LIKE, `scheduled` table, migration to version 2 |
| Firebase | Firestore (users, chats, messages, block/mute), **Realtime Database** (`data/Presence`, `data/Rtdb`, `data/RtdbLiveData`), Auth |
| Architecture | `LiveData` wrappers (`UsersLiveData`, `StatusLiveData`, `RtdbLiveData`) attach listeners only while a screen is visible |

## Project structure
```
app/src/main/java/com/barta/app/
  BartaApp          Application: theme + online/offline when app opens/closes
  model/            User, Chat, Message, UserStatus
  data/             Rtdb, Presence, RtdbLiveData, StatusLiveData, UsersLiveData
  db/               Room: AppDatabase (v2), MessageDao, MessageEntity, ScheduledEntity
  adapter/          MessageAdapter, ChatAdapter, UserAdapter
  ui/               Splash, Login, Register, Main (+Chats, Users, Profile), Chat, CreateGroup, Call
  service/          MessageListenerService, CallService
  call/             WebRtcClient, CallSignaling, CallAudio, CallConfig (STUN/TURN), IncomingCallNotifier
  work/             ScheduledMessageWorker
  receiver/         NetworkReceiver, CallActionReceiver
  view/             AvatarView
  util/             ImageUtil, ChatUtil, MessageSender, MessageMapper, LocationHelper, QuoteFetcher, AppExecutors
firestore.rules       paste into Firestore > Rules
database.rules.json   paste into Realtime Database > Rules
```

## Data model
Firestore
```
users/{uid}                       name, email, about, photoBase64, blocked[], muted[]
chats/{chatId}                    members[], memberNames{uid:name}, group, groupName, lastMessage, lastSenderId, updatedAt
chats/{chatId}/messages/{msgId}   senderId, senderName, text, imageBase64, lat, lng, timestamp,
                                  replyToId, replyToSender, replyToText, edited, deleted, reactions{uid:emoji}
```
Realtime Database
```
status/{uid}               { online: bool, lastSeen: server time }
typing/{chatId}/{uid}      true
reads/{chatId}/{uid}       newest message time I have seen
userReads/{uid}/{chatId}   same value, grouped per user (for the chat list)
calls/{calleeUid}          { callId, callerId, callerName, video, createdAt }   "someone is calling you"
callData/{callId}          { callerId, calleeId, callerName, video, status, createdAt,
                             offer{type,sdp}, answer{type,sdp}, callerCandidates{..}, calleeCandidates{..} }
```
Call messages in Firestore also have `callType` (audio/video), `callStatus` (completed/missed/declined/busy), `callDuration` (seconds).
Direct chat id = the two uids sorted and joined with `_`. Group chats use an auto id.

## Known limits (because it is free)
- Notifications arrive while the app process is alive (foreground service). If Android kills the app,
  there are no notifications until it is opened again.
- Scheduled messages are sent by the phone that scheduled them. Android may delay them by a few
  minutes in battery-saving mode; they need internet at that time.
- Photos are small (chat 800 px, profile 256 px) because Firestore documents are limited to 1 MB.
- Realtime Database: max 100 phones connected at the same time on Spark. Fine for a class project.
- Typing / seen data in the Realtime Database is readable by any signed-in user who knows the chat id
  (Realtime Database rules cannot look into Firestore to check membership). Messages themselves stay private.
- Search covers messages that this phone has already downloaded (open the chat once online).

## Troubleshooting
- **App crashes on start with "Specify DatabaseURL"**: you skipped step 2.6 - download `google-services.json` again.
- **"online" / "typing" never shows**: publish `database.rules.json` (step 2.5); locked mode blocks everything.
- **Reactions / edit / delete fail with PERMISSION_DENIED**: publish the new `firestore.rules`.
- **Location says "Turn on Location"**: enable Location in the phone's quick settings.
- **Call says "... is on another call" every time**: publish the new `database.rules.json` (v3 adds `calls` / `callData`).
- **Call rings but stays on "Connecting..." then "Couldn't connect"**: the phones can't reach each other directly. Put both on Wi-Fi, or add free TURN details in `CallConfig.java`.
- **Video is black on one side**: allow the Camera permission on that phone; an emulator needs a camera set in its AVD settings.
- **No sound on the earpiece**: tap the speaker button; some emulators only play through the speaker.
- **No notifications**: allow the notification permission (Android 13+) and keep the app running in the background.

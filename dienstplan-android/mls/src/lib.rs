//! MLS-Gruppenverschlüsselung (RFC 9420) für die Dienstplan-App.
//!
//! Dünne Schicht über dem Marmot Development Kit (MDK, OpenMLS + rust-nostr): Die App übergibt
//! und erhält Nostr-Events als JSON und spricht selbst mit den Relays. Sämtliche Kryptografie
//! stammt aus MDK, OpenMLS und rust-nostr; dieser Code verbindet nur die Bausteine.
//!
//! Der MLS-Zustand liegt in einer SQLCipher-Datenbank, deren Schlüssel die App mitgibt (auf
//! Android durch den Keystore geschützt). Der Identitätsschlüssel des Geräts wird ebenfalls von
//! der App verwaltet und nur im Speicher gehalten.

uniffi::setup_scaffolding!();

use std::collections::VecDeque;
use std::sync::{Arc, Mutex, MutexGuard};

use mdk_core::MdkConfig;
use mdk_core::callback::{MdkCallback, RollbackInfo};
use mdk_core::prelude::*;
use mdk_sqlite_storage::{EncryptionConfig, MdkSqliteStorage};
use mdk_storage_traits::groups::GroupStorage;
use mdk_storage_traits::groups::types::GroupState;
use nostr::nips::nip59;
use nostr::{Event, EventBuilder, EventId, JsonUtil, Keys, Kind, PublicKey, RelayUrl, SecretKey, Tag, TagKind};
use openmls_traits::OpenMlsProvider;

/// Kind der Gruppen-Events (Commits, Vorschläge, Anwendungsnachrichten).
const KIND_GROUP_MESSAGE: u16 = 445;
/// Kind der Gift Wraps (NIP-59), in denen Einladungen (Welcome) ankommen.
const KIND_GIFT_WRAP: u16 = 1059;
/// Kind der KeyPackages (adressierbar, signiert mit dem Identitätsschlüssel).
const KIND_KEY_PACKAGE: u16 = 30443;
/// Wie viele vergangene Epochen MDK für verspätete Nachrichten behält.
const PAST_EPOCHS: u64 = 5;
/// Obergrenze für zurückgestellte Events (Schutz vor Müll mit passendem h-Tag).
const MAX_DEFERRED: usize = 500;
/// Wie oft ein zurückgestelltes Event höchstens erneut versucht wird.
const MAX_DEFER_ATTEMPTS: u8 = 20;
/// Toleranz für vorgehende Uhren anderer Geräte (MDK-Standard: 5 Minuten).
const MAX_FUTURE_SKEW_SECS: u64 = 60 * 60;
/// Höchstlänge der Teambeschreibung in Bytes (dort liegt der Sperrstatus des Plans).
const MAX_DESCRIPTION_BYTES: usize = 4096;
/// Kinds, die [MlsEngine::sign_event] mit dem Identitätsschlüssel signiert:
/// Löschanfragen (NIP-09) und Anmeldungen bei Relays (NIP-42).
const SIGNABLE_KINDS: [u16; 2] = [5, 22242];

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum MlsError {
    #[error("Ungültige Eingabe: {reason}")]
    InvalidInput { reason: String },
    #[error("Speicher nicht verfügbar: {reason}")]
    Storage { reason: String },
    #[error("MLS-Fehler: {reason}")]
    Protocol { reason: String },
    #[error("Nicht gefunden: {reason}")]
    NotFound { reason: String },
}

fn invalid(reason: impl Into<String>) -> MlsError {
    MlsError::InvalidInput { reason: reason.into() }
}

fn protocol(error: impl std::fmt::Display) -> MlsError {
    MlsError::Protocol { reason: error.to_string() }
}

/// Zustand eines Teams (einer MLS-Gruppe) aus Sicht dieses Geräts.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct TeamInfo {
    /// MLS-Gruppen-ID (hex), ändert sich nie.
    pub group_id: String,
    /// ID im h-Tag der Gruppen-Events (hex).
    pub nostr_group_id: String,
    pub name: String,
    pub epoch: u64,
    /// false: dieses Gerät wurde entfernt oder hat das Team verlassen.
    pub active: bool,
    /// Öffentliche Schlüssel aller Geräte (hex), sortiert.
    pub members: Vec<String>,
    /// Öffentliche Schlüssel der Admins (hex), sortiert.
    pub admins: Vec<String>,
    pub relays: Vec<String>,
    /// Beschreibung der Gruppe. Die App legt dort den Sperrstatus des Plans ab (JSON). Nur
    /// Admins können sie ändern: MDK lehnt Commits anderer Geräte beim Empfang ab.
    pub description: String,
}

/// Ergebnis einer Einladung: zuerst den Commit veröffentlichen, nach der Bestätigung
/// [MlsEngine::confirm_published] aufrufen und erst dann die Gift Wraps senden.
#[derive(Debug, Clone, uniffi::Record)]
pub struct Invitation {
    pub commit_event: String,
    pub welcome_events: Vec<String>,
    pub invitee: String,
}

/// Eine eingegangene, noch nicht angenommene Einladung.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Record)]
pub struct PendingInvite {
    /// ID der Welcome-Nachricht (hex), dient zum Annehmen oder Ablehnen.
    pub invite_id: String,
    pub group_name: String,
    /// Gerät, das eingeladen hat (hex).
    pub inviter: String,
    pub admins: Vec<String>,
    pub member_count: u32,
    /// Zeitpunkt der Einladung (Sekunden seit 1970); ältere Gruppen-Events sind für das
    /// neue Gerät ohnehin nicht lesbar.
    pub created_at: u64,
}

/// Was beim Verarbeiten eines Gruppen-Events herausgekommen ist.
#[derive(Debug, Clone, PartialEq, Eq, uniffi::Enum)]
pub enum IngestOutcome {
    /// Entschlüsselte Anwendungsnachricht eines Mitglieds (auch eigene, wenn das Relay sie zurückspielt).
    AppMessage {
        group_id: String,
        event_id: String,
        sender: String,
        kind: u16,
        content: String,
        created_at: u64,
    },
    /// Ein Commit wurde übernommen (neue Epoche, Mitglieder oder Admins geändert).
    GroupChanged { group_id: String },
    /// Ein Admin-Gerät hat einen Austritt automatisch bestätigt: Event veröffentlichen,
    /// danach [MlsEngine::confirm_published].
    PublishRequired { group_id: String, event: String },
    /// Noch nicht entschlüsselbar (Commit fehlt noch); wird später erneut versucht.
    Deferred { event_id: String },
    /// Verworfen: fremd, doppelt, ungültig oder nicht verarbeitbar.
    Ignored { event_id: String, reason: String },
    /// Ein früherer Commit derselben Epoche hat gewonnen (MIP-03): Der Zustand wurde
    /// zurückgesetzt. Nachrichten aus der verworfenen Epoche erreichen nicht alle Geräte;
    /// die App sollte ihren Stand erneut abgleichen.
    RolledBack { group_id: String },
}

struct DeferredEvent {
    event: Event,
    attempts: u8,
}

struct Inner {
    mdk: MDK<MdkSqliteStorage>,
    keys: Keys,
    deferred: VecDeque<DeferredEvent>,
    rollbacks: Arc<RollbackLog>,
}

/// Sammelt Rücksprünge, die MDK während der Verarbeitung meldet.
#[derive(Debug, Default)]
struct RollbackLog(Mutex<Vec<String>>);

impl MdkCallback for RollbackLog {
    fn on_rollback(&self, info: &RollbackInfo) {
        if let Ok(mut groups) = self.0.lock() {
            groups.push(hex::encode(info.group_id.as_slice()));
        }
    }
}

impl RollbackLog {
    fn drain(&self) -> Vec<String> {
        self.0.lock().map(|mut groups| std::mem::take(&mut *groups)).unwrap_or_default()
    }
}

/// Zugang zum MLS-Zustand dieses Geräts. Alle Aufrufe sind serialisiert.
#[derive(uniffi::Object)]
pub struct MlsEngine {
    inner: Mutex<Inner>,
}

#[uniffi::export]
impl MlsEngine {
    /// Öffnet (oder erstellt) die verschlüsselte Datenbank.
    ///
    /// * `db_key` – 32 zufällige Bytes, von der App geschützt aufbewahrt.
    /// * `identity_secret` – 32-Byte-secp256k1-Schlüssel dieses Geräts.
    #[uniffi::constructor]
    pub fn open(db_path: String, db_key: Vec<u8>, identity_secret: Vec<u8>) -> Result<Arc<Self>, MlsError> {
        let key: [u8; 32] = db_key.as_slice().try_into().map_err(|_| invalid("Datenbankschlüssel muss 32 Bytes haben"))?;
        let secret = SecretKey::from_slice(&identity_secret).map_err(|_| invalid("Identitätsschlüssel ungültig"))?;
        let storage = MdkSqliteStorage::new_with_key(&db_path, EncryptionConfig::new(key))
            .map_err(|e| MlsError::Storage { reason: e.to_string() })?;
        let mut config = MdkConfig::default();
        config.max_future_skew_secs = MAX_FUTURE_SKEW_SECS;
        let rollbacks = Arc::new(RollbackLog::default());
        let callback: Arc<dyn MdkCallback> = rollbacks.clone();
        let mdk = MDK::builder(storage).with_config(config).with_callback(callback).build();
        Ok(Arc::new(Self {
            inner: Mutex::new(Inner { mdk, keys: Keys::new(secret), deferred: VecDeque::new(), rollbacks }),
        }))
    }

    /// Öffentlicher Identitätsschlüssel dieses Geräts (hex).
    pub fn public_key(&self) -> Result<String, MlsError> {
        Ok(self.lock()?.keys.public_key().to_hex())
    }

    /// Neues KeyPackage (Kind 30443), signiert mit dem Identitätsschlüssel. Andere Admins
    /// laden es von den Relays, um dieses Gerät einzuladen.
    pub fn key_package_event(&self, relays: Vec<String>) -> Result<String, MlsError> {
        let inner = self.lock()?;
        let relays = parse_relays(&relays)?;
        let pubkey = inner.keys.public_key();
        let data = inner.mdk.create_key_package_for_event(&pubkey, relays).map_err(protocol)?;
        let event = EventBuilder::new(Kind::Custom(KIND_KEY_PACKAGE), data.content)
            .tags(data.tags_30443)
            .build(pubkey)
            .sign_with_keys(&inner.keys)
            .map_err(protocol)?;
        Ok(event.as_json())
    }

    /// Neues Team mit diesem Gerät als einzigem Mitglied und Admin.
    pub fn create_team(&self, name: String, relays: Vec<String>) -> Result<TeamInfo, MlsError> {
        let inner = self.lock()?;
        let relays = parse_relays(&relays)?;
        let me = inner.keys.public_key();
        let config = NostrGroupConfigData::new(name, String::new(), None, None, None, relays, vec![me], None);
        let created = inner.mdk.create_group(&me, Vec::new(), config).map_err(protocol)?;
        team_info(&inner.mdk, &created.group.mls_group_id)
    }

    /// Lädt ein Gerät ein (nur Admins). `key_package_event` ist das Kind-30443-Event des Geräts.
    pub fn invite(&self, group_id: String, key_package_event: String) -> Result<Invitation, MlsError> {
        let inner = self.lock()?;
        let gid = parse_group_id(&group_id)?;
        let kp = parse_event(&key_package_event)?;
        if kp.kind.as_u16() != KIND_KEY_PACKAGE {
            return Err(invalid("kein KeyPackage-Event"));
        }
        let invitee = kp.pubkey;
        let result = inner.mdk.add_members(&gid, std::slice::from_ref(&kp)).map_err(protocol)?;
        let mut welcome_events = Vec::new();
        for rumor in result.welcome_rumors.unwrap_or_default() {
            let wrap = pollster::block_on(EventBuilder::gift_wrap(&inner.keys, &invitee, rumor, []))
                .map_err(protocol)?;
            welcome_events.push(wrap.as_json());
        }
        Ok(Invitation { commit_event: result.evolution_event.as_json(), welcome_events, invitee: invitee.to_hex() })
    }

    /// Entfernt Geräte aus dem Team (nur Admins). Liefert den zu veröffentlichenden Commit.
    pub fn remove_members(&self, group_id: String, pubkeys: Vec<String>) -> Result<String, MlsError> {
        let inner = self.lock()?;
        let gid = parse_group_id(&group_id)?;
        let pubkeys = parse_pubkeys(&pubkeys)?;
        let result = inner.mdk.remove_members(&gid, &pubkeys).map_err(protocol)?;
        Ok(result.evolution_event.as_json())
    }

    /// Setzt die Admin-Liste neu (nur Admins). Liefert den zu veröffentlichenden Commit.
    pub fn set_admins(&self, group_id: String, admins: Vec<String>) -> Result<String, MlsError> {
        self.update_team(group_id, Some(admins), None)
    }

    /// Ändert Admin-Liste und/oder Beschreibung in einem einzigen Commit (nur Admins);
    /// `None` lässt den Wert, wie er ist. Liefert den zu veröffentlichenden Commit.
    pub fn update_team(
        &self,
        group_id: String,
        admins: Option<Vec<String>>,
        description: Option<String>,
    ) -> Result<String, MlsError> {
        let inner = self.lock()?;
        let gid = parse_group_id(&group_id)?;
        let mut update = NostrGroupDataUpdate::new();
        if let Some(admins) = admins {
            update.admins = Some(parse_pubkeys(&admins)?);
        }
        if let Some(description) = description {
            if description.len() > MAX_DESCRIPTION_BYTES {
                return Err(invalid("Beschreibung zu lang"));
            }
            update.description = Some(description);
        }
        if update.admins.is_none() && update.description.is_none() {
            return Err(invalid("nichts zu ändern"));
        }
        let result = inner.mdk.update_group_data(&gid, update).map_err(protocol)?;
        Ok(result.evolution_event.as_json())
    }

    /// Erneuert den eigenen Schlüssel in der Gruppe (Forward Secrecy, Post-Compromise Security).
    pub fn self_update(&self, group_id: String) -> Result<String, MlsError> {
        let inner = self.lock()?;
        let gid = parse_group_id(&group_id)?;
        let result = inner.mdk.self_update(&gid).map_err(protocol)?;
        Ok(result.evolution_event.as_json())
    }

    /// Gibt die eigenen Admin-Rechte ab (nötig vor dem Verlassen).
    pub fn self_demote(&self, group_id: String) -> Result<String, MlsError> {
        let inner = self.lock()?;
        let gid = parse_group_id(&group_id)?;
        let result = inner.mdk.self_demote(&gid).map_err(protocol)?;
        Ok(result.evolution_event.as_json())
    }

    /// Austritt: liefert einen Vorschlag, den ein Admin-Gerät automatisch bestätigt.
    pub fn leave(&self, group_id: String) -> Result<String, MlsError> {
        let inner = self.lock()?;
        let gid = parse_group_id(&group_id)?;
        let result = inner.mdk.leave_group(&gid).map_err(protocol)?;
        Ok(result.evolution_event.as_json())
    }

    /// Der eigene Commit ist veröffentlicht: übernehmen. Danach werden zurückgestellte
    /// Events erneut versucht; deren Ergebnisse kommen zurück.
    pub fn confirm_published(&self, group_id: String) -> Result<Vec<IngestOutcome>, MlsError> {
        let mut inner = self.lock()?;
        let gid = parse_group_id(&group_id)?;
        inner.mdk.merge_pending_commit(&gid).map_err(protocol)?;
        let mut outcomes = Vec::new();
        inner.retry_deferred(&mut outcomes);
        Ok(outcomes)
    }

    /// Der eigene Commit konnte nicht veröffentlicht werden: verwerfen. Zurückgestellte
    /// Events werden danach erneut versucht; deren Ergebnisse kommen zurück.
    pub fn abort_pending(&self, group_id: String) -> Result<Vec<IngestOutcome>, MlsError> {
        let mut inner = self.lock()?;
        let gid = parse_group_id(&group_id)?;
        inner.mdk.clear_pending_commit(&gid).map_err(protocol)?;
        let mut outcomes = Vec::new();
        inner.retry_deferred(&mut outcomes);
        Ok(outcomes)
    }

    /// Prüft einen Gift Wrap (Kind 1059). Enthält er eine Einladung, wird sie gespeichert
    /// und zurückgegeben; alles andere ergibt `None`.
    pub fn receive_invite(&self, gift_wrap_event: String) -> Result<Option<PendingInvite>, MlsError> {
        let inner = self.lock()?;
        let wrap = parse_event(&gift_wrap_event)?;
        if wrap.kind.as_u16() != KIND_GIFT_WRAP {
            return Ok(None);
        }
        let Ok(unwrapped) = pollster::block_on(nip59::extract_rumor(&inner.keys, &wrap)) else {
            return Ok(None); // nicht für uns oder beschädigt
        };
        if unwrapped.rumor.kind.as_u16() != 444 || unwrapped.rumor.pubkey != unwrapped.sender {
            return Ok(None);
        }
        // Doppelt zugestellte Gift Wraps erkennt MDK an der Wrapper-ID und liefert die gespeicherte Einladung.
        let welcome = inner.mdk.process_welcome(&wrap.id, &unwrapped.rumor).map_err(protocol)?;
        Ok(Some(pending_invite(&welcome)))
    }

    /// Nimmt eine Einladung an. Danach sollte das Gerät [MlsEngine::self_update] senden.
    pub fn accept_invite(&self, invite_id: String) -> Result<TeamInfo, MlsError> {
        let inner = self.lock()?;
        let welcome = find_welcome(&inner.mdk, &invite_id)?;
        inner.mdk.accept_welcome(&welcome).map_err(protocol)?;
        team_info(&inner.mdk, &welcome.mls_group_id)
    }

    pub fn decline_invite(&self, invite_id: String) -> Result<(), MlsError> {
        let inner = self.lock()?;
        let welcome = find_welcome(&inner.mdk, &invite_id)?;
        inner.mdk.decline_welcome(&welcome).map_err(protocol)
    }

    /// Eingegangene, noch nicht beantwortete Einladungen (bleiben über Neustarts erhalten).
    pub fn pending_invites(&self) -> Result<Vec<PendingInvite>, MlsError> {
        let inner = self.lock()?;
        let welcomes = inner.mdk.get_pending_welcomes(None).map_err(protocol)?;
        Ok(welcomes.iter().map(pending_invite).collect())
    }

    /// Signiert ein Event mit dem Identitätsschlüssel. Nur für Löschanfragen (Kind 5) und
    /// Relay-Anmeldungen (Kind 22242); alles andere läuft über MLS.
    pub fn sign_event(&self, kind: u16, tags: Vec<Vec<String>>, content: String) -> Result<String, MlsError> {
        if !SIGNABLE_KINDS.contains(&kind) {
            return Err(invalid("dieses Kind wird nicht signiert"));
        }
        if tags.len() > 20 || content.len() > 1024 {
            return Err(invalid("Event zu gross"));
        }
        let mut parsed = Vec::with_capacity(tags.len());
        for tag in tags {
            if tag.is_empty() || tag.len() > 4 || tag.iter().any(|v| v.len() > 512) {
                return Err(invalid("Tag ungültig"));
            }
            parsed.push(Tag::parse(tag).map_err(|_| invalid("Tag ungültig"))?);
        }
        let inner = self.lock()?;
        let event = EventBuilder::new(Kind::Custom(kind), content)
            .tags(parsed)
            .build(inner.keys.public_key())
            .sign_with_keys(&inner.keys)
            .map_err(protocol)?;
        Ok(event.as_json())
    }

    /// Verschlüsselt eine Anwendungsnachricht. Liefert das zu veröffentlichende Kind-445-Event.
    pub fn encrypt(&self, group_id: String, kind: u16, content: String) -> Result<String, MlsError> {
        let inner = self.lock()?;
        let gid = parse_group_id(&group_id)?;
        let rumor = EventBuilder::new(Kind::Custom(kind), content).build(inner.keys.public_key());
        let event = inner.mdk.create_message(&gid, rumor, None).map_err(protocol)?;
        Ok(event.as_json())
    }

    /// Verarbeitet Gruppen-Events (Kind 445) in zeitlicher Reihenfolge. Events aus einer noch
    /// unbekannten Epoche werden zurückgestellt und nach jedem übernommenen Commit erneut versucht.
    pub fn ingest(&self, events: Vec<String>) -> Result<Vec<IngestOutcome>, MlsError> {
        let mut inner = self.lock()?;
        let mut outcomes = Vec::new();
        let mut parsed = Vec::new();
        for json in &events {
            match Event::from_json(json) {
                Ok(event) if event.verify().is_ok() => parsed.push(event),
                Ok(event) => outcomes.push(ignored(&event.id, "Signatur ungültig")),
                Err(_) => outcomes.push(IngestOutcome::Ignored { event_id: String::new(), reason: "kein Event".into() }),
            }
        }
        parsed.sort_by(|a, b| a.created_at.cmp(&b.created_at).then_with(|| a.id.cmp(&b.id)));
        parsed.dedup_by(|a, b| a.id == b.id);
        let mut advanced = false;
        let mut remaining = parsed.into_iter();
        while let Some(event) = remaining.next() {
            advanced |= inner.process(event, 0, &mut outcomes);
            if awaits_publication(&outcomes) {
                // Ein automatischer Commit wartet auf seine Veröffentlichung. Bis zur Bestätigung
                // bleibt der Rest liegen, damit kein fremder Commit dazwischenkommt.
                inner.defer_all(remaining, &mut outcomes);
                return Ok(outcomes);
            }
        }
        if advanced || !inner.deferred.is_empty() {
            inner.retry_deferred(&mut outcomes);
        }
        Ok(outcomes)
    }

    pub fn team(&self, group_id: String) -> Result<Option<TeamInfo>, MlsError> {
        let inner = self.lock()?;
        let gid = parse_group_id(&group_id)?;
        if inner.mdk.get_group(&gid).map_err(protocol)?.is_none() {
            return Ok(None);
        }
        team_info(&inner.mdk, &gid).map(Some)
    }

    pub fn teams(&self) -> Result<Vec<TeamInfo>, MlsError> {
        let inner = self.lock()?;
        let groups = inner.mdk.get_groups().map_err(protocol)?;
        groups.iter().map(|g| team_info(&inner.mdk, &g.mls_group_id)).collect()
    }

    /// Teams, in denen der eigene Schlüssel älter als `threshold_secs` ist oder nach dem
    /// Beitritt noch nicht erneuert wurde.
    pub fn teams_needing_self_update(&self, threshold_secs: u64) -> Result<Vec<String>, MlsError> {
        let inner = self.lock()?;
        let ids = inner.mdk.groups_needing_self_update(threshold_secs).map_err(protocol)?;
        Ok(ids.iter().map(|id| hex::encode(id.as_slice())).collect())
    }

    /// Löscht den lokalen Zustand eines Teams (nach Entfernen oder Verlassen).
    pub fn delete_team(&self, group_id: String) -> Result<(), MlsError> {
        let mut inner = self.lock()?;
        let gid = parse_group_id(&group_id)?;
        inner.deferred.clear();
        inner.mdk.delete_group(&gid).map_err(protocol)
    }

    /// Anzahl zurückgestellter Events (für die Diagnose).
    pub fn deferred_count(&self) -> Result<u32, MlsError> {
        Ok(self.lock()?.deferred.len() as u32)
    }

    /// Epoche, falls das Gruppen-Event ein lesbarer Commit ist – ohne ihn zu verarbeiten.
    ///
    /// Die verwendete MDK-Version löst konkurrierende Commits (MIP-03) nur für fremde Commits
    /// auf. Die App prüft damit vor dem Übernehmen eines eigenen Commits, ob ein früherer für
    /// dieselbe Epoche eingetroffen ist, und verwirft dann den eigenen.
    pub fn peek_commit(&self, event: String) -> Result<Option<u64>, MlsError> {
        let inner = self.lock()?;
        let Ok(event) = Event::from_json(&event) else { return Ok(None) };
        if event.kind.as_u16() != KIND_GROUP_MESSAGE || event.verify().is_err() {
            return Ok(None);
        }
        Ok(match outer_layer(&inner.mdk, &event) {
            OuterLayer::Readable(plaintext) => commit_epoch(&plaintext),
            _ => None,
        })
    }
}

impl MlsEngine {
    fn lock(&self) -> Result<MutexGuard<'_, Inner>, MlsError> {
        self.inner.lock().map_err(|_| MlsError::Storage { reason: "Zustand nach Absturz gesperrt".into() })
    }
}

/// Prüft 32 Bytes als secp256k1-Schlüssel.
#[uniffi::export]
pub fn is_valid_identity_secret(secret: Vec<u8>) -> bool {
    SecretKey::from_slice(&secret).is_ok()
}

/// Öffentlicher Schlüssel (hex) zu einem Identitätsschlüssel.
#[uniffi::export]
pub fn public_key_of(secret: Vec<u8>) -> Result<String, MlsError> {
    let secret = SecretKey::from_slice(&secret).map_err(|_| invalid("Identitätsschlüssel ungültig"))?;
    Ok(Keys::new(secret).public_key().to_hex())
}

impl Inner {
    /// Verarbeitet ein Event. `true`, wenn ein Commit übernommen wurde.
    fn process(&mut self, event: Event, attempts: u8, outcomes: &mut Vec<IngestOutcome>) -> bool {
        if event.kind.as_u16() != KIND_GROUP_MESSAGE {
            outcomes.push(ignored(&event.id, "kein Gruppen-Event"));
            return false;
        }
        match outer_layer(&self.mdk, &event) {
            OuterLayer::UnknownGroup => {
                outcomes.push(ignored(&event.id, "fremde Gruppe"));
                return false;
            }
            OuterLayer::NotYet => {
                if attempts >= MAX_DEFER_ATTEMPTS {
                    outcomes.push(ignored(&event.id, "nicht entschlüsselbar"));
                } else {
                    if self.deferred.len() >= MAX_DEFERRED {
                        self.deferred.pop_front();
                    }
                    outcomes.push(IngestOutcome::Deferred { event_id: event.id.to_hex() });
                    self.deferred.push_back(DeferredEvent { event, attempts: attempts + 1 });
                }
                return false;
            }
            OuterLayer::Readable(_) => {}
        }
        let result = self.mdk.process_message(&event);
        for group_id in self.rollbacks.drain() {
            outcomes.push(IngestOutcome::RolledBack { group_id });
        }
        match result {
            Ok(MessageProcessingResult::ApplicationMessage(message)) => {
                outcomes.push(IngestOutcome::AppMessage {
                    group_id: hex::encode(message.mls_group_id.as_slice()),
                    event_id: event.id.to_hex(),
                    sender: message.pubkey.to_hex(),
                    kind: message.kind.as_u16(),
                    content: message.content,
                    created_at: message.created_at.as_secs(),
                });
                false
            }
            Ok(MessageProcessingResult::Commit { mls_group_id }) => {
                outcomes.push(IngestOutcome::GroupChanged { group_id: hex::encode(mls_group_id.as_slice()) });
                true
            }
            Ok(MessageProcessingResult::Proposal(update)) => {
                outcomes.push(IngestOutcome::PublishRequired {
                    group_id: hex::encode(update.mls_group_id.as_slice()),
                    event: update.evolution_event.as_json(),
                });
                false
            }
            Ok(MessageProcessingResult::PendingProposal { .. }) => {
                outcomes.push(ignored(&event.id, "Vorschlag wartet auf einen Admin"));
                false
            }
            Ok(MessageProcessingResult::IgnoredProposal { reason, .. }) => {
                outcomes.push(ignored(&event.id, &format!("Vorschlag ignoriert: {reason}")));
                false
            }
            Ok(MessageProcessingResult::ExternalJoinProposal { .. }) => {
                outcomes.push(ignored(&event.id, "externer Beitritt nicht vorgesehen"));
                false
            }
            Ok(MessageProcessingResult::Unprocessable { .. }) => {
                outcomes.push(ignored(&event.id, "nicht verarbeitbar"));
                false
            }
            Ok(MessageProcessingResult::PreviouslyFailed) => {
                outcomes.push(ignored(&event.id, "früher fehlgeschlagen"));
                false
            }
            Err(error) => {
                outcomes.push(ignored(&event.id, &error.to_string()));
                false
            }
        }
    }

    /// Stellt Events ohne Versuch zurück (sie kommen nach der nächsten Bestätigung dran).
    fn defer_all(&mut self, events: impl Iterator<Item = Event>, outcomes: &mut Vec<IngestOutcome>) {
        for event in events {
            if self.deferred.len() >= MAX_DEFERRED {
                self.deferred.pop_front();
            }
            outcomes.push(IngestOutcome::Deferred { event_id: event.id.to_hex() });
            self.deferred.push_back(DeferredEvent { event, attempts: 0 });
        }
    }

    /// Versucht zurückgestellte Events erneut, solange dabei Commits übernommen werden.
    fn retry_deferred(&mut self, outcomes: &mut Vec<IngestOutcome>) {
        loop {
            if self.deferred.is_empty() {
                return;
            }
            let mut pending: Vec<DeferredEvent> = self.deferred.drain(..).collect();
            pending.sort_by(|a, b| a.event.created_at.cmp(&b.event.created_at).then_with(|| a.event.id.cmp(&b.event.id)));
            let mut advanced = false;
            let mut remaining = pending.into_iter();
            while let Some(item) = remaining.next() {
                // Erneut zurückgestellte Events nicht nochmals melden.
                let mut local = Vec::new();
                advanced |= self.process(item.event, item.attempts, &mut local);
                let paused = awaits_publication(&local);
                outcomes.extend(local.into_iter().filter(|o| !matches!(o, IngestOutcome::Deferred { .. })));
                if paused {
                    self.deferred.extend(remaining);
                    return;
                }
            }
            if !advanced {
                return;
            }
        }
    }
}

enum OuterLayer {
    UnknownGroup,
    NotYet,
    /// Entschlüsselte MLS-Nachricht (noch nicht verarbeitet).
    Readable(Vec<u8>),
}

/// MDK markiert Events aus einer noch unbekannten Epoche dauerhaft als fehlgeschlagen. Deshalb
/// wird die äussere Schicht (ChaCha20-Poly1305 mit dem Exporter-Secret einer Epoche) vorab mit
/// den gespeicherten Schlüsseln der aktuellen und der letzten Epochen geprüft.
fn outer_layer(mdk: &MDK<MdkSqliteStorage>, event: &Event) -> OuterLayer {
    use base64::Engine;
    use chacha20poly1305::aead::{Aead, KeyInit};
    use chacha20poly1305::{ChaCha20Poly1305, Nonce};

    let storage = mdk.provider.storage();
    let Some(h) = event.tags.iter().find(|t| t.kind() == TagKind::h()).and_then(|t| t.content()) else {
        return OuterLayer::UnknownGroup;
    };
    let Ok(raw) = hex::decode(h) else { return OuterLayer::UnknownGroup };
    let Ok(nostr_group_id): Result<[u8; 32], _> = raw.try_into() else { return OuterLayer::UnknownGroup };
    let Ok(Some(group)) = storage.find_group_by_nostr_group_id(&nostr_group_id) else {
        return OuterLayer::UnknownGroup;
    };
    let Ok(bytes) = base64::engine::general_purpose::STANDARD.decode(&event.content) else {
        return OuterLayer::NotYet;
    };
    if bytes.len() < 12 + 16 {
        return OuterLayer::NotYet;
    }
    let (nonce, ciphertext) = bytes.split_at(12);
    let open = |key: &[u8]| {
        ChaCha20Poly1305::new_from_slice(key)
            .ok()
            .and_then(|cipher| cipher.decrypt(Nonce::from_slice(nonce), ciphertext).ok())
    };
    // Aktuelle Epoche: wie MDK aus dem lebenden MLS-Zustand ableiten (MLS-Exporter
    // "marmot"/"group-event"); gespeichert wird dieser Schlüssel erst bei Bedarf.
    let current: Option<Vec<u8>> = openmls::group::MlsGroup::load(storage, group.mls_group_id.inner())
        .ok()
        .flatten()
        .and_then(|mls| mls.export_secret(mdk.provider.crypto(), "marmot", b"group-event", 32).ok());
    if let Some(plaintext) = current.and_then(|key| open(&key)) {
        return OuterLayer::Readable(plaintext);
    }
    // Vergangene Epochen: nur, was MDK gespeichert hat (genau das kann MDK auch entschlüsseln).
    for epoch in (group.epoch.saturating_sub(PAST_EPOCHS)..=group.epoch).rev() {
        if let Ok(Some(secret)) = storage.get_group_exporter_secret(&group.mls_group_id, epoch)
            && let Some(plaintext) = open(secret.secret.as_ref())
        {
            return OuterLayer::Readable(plaintext);
        }
    }
    OuterLayer::NotYet
}

/// Epoche eines Commits, ohne ihn zu verarbeiten (Epoche und Inhaltstyp stehen in MLS im Klartext
/// der inneren Nachricht). `None` für alles andere oder Unlesbares.
fn commit_epoch(plaintext: &[u8]) -> Option<u64> {
    use openmls::prelude::{ContentType, MlsMessageIn};
    use openmls::prelude::tls_codec::Deserialize;
    let message = MlsMessageIn::tls_deserialize_exact(plaintext).ok()?.try_into_protocol_message().ok()?;
    (message.content_type() == ContentType::Commit).then(|| message.epoch().as_u64())
}

fn team_info(mdk: &MDK<MdkSqliteStorage>, gid: &GroupId) -> Result<TeamInfo, MlsError> {
    let group = mdk.get_group(gid).map_err(protocol)?.ok_or(MlsError::NotFound { reason: "Team".into() })?;
    let members = mdk.get_members(gid).map(|set| set.iter().map(|pk| pk.to_hex()).collect()).unwrap_or_default();
    let relays = mdk.get_relays(gid).map(|set| set.iter().map(|r| r.to_string()).collect()).unwrap_or_default();
    Ok(TeamInfo {
        group_id: hex::encode(gid.as_slice()),
        nostr_group_id: hex::encode(group.nostr_group_id),
        name: group.name.clone(),
        epoch: group.epoch,
        active: group.state == GroupState::Active,
        members,
        admins: group.admin_pubkeys.iter().map(|pk| pk.to_hex()).collect(),
        relays,
        description: group.description.clone(),
    })
}

fn pending_invite(welcome: &welcome_types::Welcome) -> PendingInvite {
    PendingInvite {
        invite_id: welcome.id.to_hex(),
        group_name: welcome.group_name.clone(),
        inviter: welcome.welcomer.to_hex(),
        admins: welcome.group_admin_pubkeys.iter().map(|pk| pk.to_hex()).collect(),
        member_count: welcome.member_count,
        created_at: welcome.event.created_at.as_secs(),
    }
}

/// true, wenn das zuletzt gemeldete Ergebnis ein noch unveröffentlichter Commit ist.
fn awaits_publication(outcomes: &[IngestOutcome]) -> bool {
    matches!(outcomes.last(), Some(IngestOutcome::PublishRequired { .. }))
}

fn find_welcome(mdk: &MDK<MdkSqliteStorage>, invite_id: &str) -> Result<welcome_types::Welcome, MlsError> {
    let id = EventId::from_hex(invite_id).map_err(|_| invalid("Einladungs-ID ungültig"))?;
    mdk.get_welcome(&id).map_err(protocol)?.ok_or(MlsError::NotFound { reason: "Einladung".into() })
}

fn ignored(id: &EventId, reason: &str) -> IngestOutcome {
    IngestOutcome::Ignored { event_id: id.to_hex(), reason: reason.to_string() }
}

fn parse_event(json: &str) -> Result<Event, MlsError> {
    let event = Event::from_json(json).map_err(|_| invalid("kein gültiges Event"))?;
    event.verify().map_err(|_| invalid("Signatur ungültig"))?;
    Ok(event)
}

fn parse_group_id(hex_id: &str) -> Result<GroupId, MlsError> {
    let bytes = hex::decode(hex_id).map_err(|_| invalid("Gruppen-ID ungültig"))?;
    if bytes.is_empty() || bytes.len() > 64 {
        return Err(invalid("Gruppen-ID ungültig"));
    }
    Ok(GroupId::from_slice(&bytes))
}

fn parse_pubkeys(values: &[String]) -> Result<Vec<PublicKey>, MlsError> {
    values.iter().map(|v| PublicKey::from_hex(v).map_err(|_| invalid("öffentlicher Schlüssel ungültig"))).collect()
}

fn parse_relays(values: &[String]) -> Result<Vec<RelayUrl>, MlsError> {
    if values.is_empty() {
        return Err(invalid("keine Relays"));
    }
    values
        .iter()
        .map(|v| {
            let url = RelayUrl::parse(v).map_err(|_| invalid("Relay-Adresse ungültig"))?;
            if !v.starts_with("wss://") {
                return Err(invalid("nur wss://-Relays"));
            }
            Ok(url)
        })
        .collect()
}

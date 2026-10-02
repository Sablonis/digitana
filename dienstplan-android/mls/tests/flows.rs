//! Abläufe der MLS-Schicht mit echten, verschlüsselten Datenbanken (SQLCipher).

use std::sync::Arc;

use dienstplan_mls::{IngestOutcome, MlsEngine, MlsError, TeamInfo, is_valid_identity_secret, public_key_of};
use tempfile::TempDir;

const RELAYS: [&str; 3] = ["wss://relay.damus.io", "wss://nos.lol", "wss://relay.primal.net"];

struct Device {
    engine: Arc<MlsEngine>,
    pubkey: String,
    db_key: Vec<u8>,
    secret: Vec<u8>,
    path: String,
}

fn secret(seed: u8) -> Vec<u8> {
    let mut bytes = vec![seed; 32];
    bytes[0] = 0x01; // deutlich unter der Gruppenordnung von secp256k1
    bytes
}

fn device(dir: &TempDir, name: &str, seed: u8) -> Device {
    let path = dir.path().join(format!("{name}.db")).to_string_lossy().into_owned();
    let db_key = vec![seed.wrapping_add(100); 32];
    let secret = secret(seed);
    let engine = MlsEngine::open(path.clone(), db_key.clone(), secret.clone()).expect("öffnen");
    let pubkey = engine.public_key().unwrap();
    Device { engine, pubkey, db_key, secret, path }
}

fn relays() -> Vec<String> {
    RELAYS.iter().map(|r| r.to_string()).collect()
}

/// Admin lädt ein, Gerät nimmt an und erneuert danach seinen Schlüssel – wie in der App.
fn join(admin: &Device, team: &TeamInfo, newcomer: &Device, others: &[&Device]) -> TeamInfo {
    let key_package = newcomer.engine.key_package_event(relays()).unwrap();
    let invitation = admin.engine.invite(team.group_id.clone(), key_package).unwrap();
    assert_eq!(invitation.invitee, newcomer.pubkey);
    for other in others {
        other.engine.ingest(vec![invitation.commit_event.clone()]).unwrap();
    }
    admin.engine.confirm_published(team.group_id.clone()).unwrap();
    assert_eq!(invitation.welcome_events.len(), 1);
    let invite = newcomer.engine.receive_invite(invitation.welcome_events[0].clone()).unwrap().expect("Einladung");
    assert_eq!(invite.inviter, admin.pubkey);
    let joined = newcomer.engine.accept_invite(invite.invite_id).unwrap();
    assert_eq!(joined.group_id, team.group_id);

    let update = newcomer.engine.self_update(team.group_id.clone()).unwrap();
    admin.engine.ingest(vec![update.clone()]).unwrap();
    for other in others {
        other.engine.ingest(vec![update.clone()]).unwrap();
    }
    newcomer.engine.confirm_published(team.group_id.clone()).unwrap();
    newcomer.engine.team(team.group_id.clone()).unwrap().unwrap()
}

fn app_messages(outcomes: &[IngestOutcome]) -> Vec<(String, String)> {
    outcomes
        .iter()
        .filter_map(|o| match o {
            IngestOutcome::AppMessage { sender, content, .. } => Some((sender.clone(), content.clone())),
            _ => None,
        })
        .collect()
}

#[test]
fn einladen_beitreten_und_nachrichten_in_beide_richtungen() {
    let dir = TempDir::new().unwrap();
    let alice = device(&dir, "alice", 2);
    let bob = device(&dir, "bob", 3);

    let team = alice.engine.create_team("Pflege".into(), relays()).unwrap();
    assert_eq!(team.members, vec![alice.pubkey.clone()]);
    assert_eq!(team.admins, vec![alice.pubkey.clone()]);
    assert_eq!(team.relays.len(), 3);

    let bob_team = join(&alice, &team, &bob, &[]);
    let alice_team = alice.engine.team(team.group_id.clone()).unwrap().unwrap();
    assert_eq!(alice_team.epoch, bob_team.epoch);
    assert_eq!(alice_team.members, bob_team.members);
    assert_eq!(alice_team.members.len(), 2);
    assert_eq!(bob_team.name, "Pflege");

    let to_bob = alice.engine.encrypt(team.group_id.clone(), 30078, r#"{"v":3}"#.into()).unwrap();
    let got = bob.engine.ingest(vec![to_bob.clone()]).unwrap();
    assert_eq!(app_messages(&got), vec![(alice.pubkey.clone(), r#"{"v":3}"#.to_string())]);

    let to_alice = bob.engine.encrypt(team.group_id.clone(), 30078, "hallo".into()).unwrap();
    let got = alice.engine.ingest(vec![to_alice]).unwrap();
    assert_eq!(app_messages(&got), vec![(bob.pubkey.clone(), "hallo".to_string())]);

    // Dasselbe Event nochmals (anderes Relay) bringt nichts Neues.
    let again = bob.engine.ingest(vec![to_bob]).unwrap();
    assert!(app_messages(&again).is_empty(), "{again:?}");
}

#[test]
fn nachricht_direkt_nach_dem_beitritt() {
    let dir = TempDir::new().unwrap();
    let alice = device(&dir, "alice", 21);
    let bob = device(&dir, "bob", 22);
    let team = alice.engine.create_team("Team".into(), relays()).unwrap();
    let invitation = alice.engine.invite(team.group_id.clone(), bob.engine.key_package_event(relays()).unwrap()).unwrap();
    alice.engine.confirm_published(team.group_id.clone()).unwrap();
    let invite = bob.engine.receive_invite(invitation.welcome_events[0].clone()).unwrap().unwrap();
    bob.engine.accept_invite(invite.invite_id).unwrap();

    // Ohne Schlüsselerneuerung dazwischen: die aktuelle Epoche muss lesbar sein.
    let message = alice.engine.encrypt(team.group_id.clone(), 30078, "willkommen".into()).unwrap();
    let outcomes = bob.engine.ingest(vec![message]).unwrap();
    assert_eq!(app_messages(&outcomes), vec![(alice.pubkey.clone(), "willkommen".to_string())], "{outcomes:?}");
    let reply = bob.engine.encrypt(team.group_id.clone(), 30078, "danke".into()).unwrap();
    assert_eq!(app_messages(&alice.engine.ingest(vec![reply]).unwrap()), vec![(bob.pubkey.clone(), "danke".to_string())]);
}

#[test]
fn entferntes_geraet_verliert_den_zugang() {
    let dir = TempDir::new().unwrap();
    let alice = device(&dir, "alice", 4);
    let bob = device(&dir, "bob", 5);
    let carol = device(&dir, "carol", 6);
    let team = alice.engine.create_team("Team".into(), relays()).unwrap();
    join(&alice, &team, &bob, &[]);
    join(&alice, &team, &carol, &[&bob]);

    let commit = alice.engine.remove_members(team.group_id.clone(), vec![bob.pubkey.clone()]).unwrap();
    alice.engine.confirm_published(team.group_id.clone()).unwrap();
    carol.engine.ingest(vec![commit.clone()]).unwrap();
    let removed = bob.engine.ingest(vec![commit]).unwrap();
    assert!(removed.iter().any(|o| matches!(o, IngestOutcome::GroupChanged { .. })), "{removed:?}");

    let bob_view = bob.engine.team(team.group_id.clone()).unwrap().unwrap();
    assert!(!bob_view.active || !bob_view.members.contains(&bob.pubkey), "{bob_view:?}");

    // Nachrichten nach dem Entfernen kann Bob nicht mehr lesen.
    let secret_message = alice.engine.encrypt(team.group_id.clone(), 30078, "nach dem Entfernen".into()).unwrap();
    assert!(app_messages(&bob.engine.ingest(vec![secret_message.clone()]).unwrap()).is_empty());
    assert_eq!(
        app_messages(&carol.engine.ingest(vec![secret_message]).unwrap()),
        vec![(alice.pubkey.clone(), "nach dem Entfernen".to_string())]
    );
    let alice_view = alice.engine.team(team.group_id.clone()).unwrap().unwrap();
    assert_eq!(alice_view.members.len(), 2);
}

#[test]
fn austritt_wird_vom_admin_automatisch_bestaetigt() {
    let dir = TempDir::new().unwrap();
    let alice = device(&dir, "alice", 7);
    let bob = device(&dir, "bob", 8);
    let team = alice.engine.create_team("Team".into(), relays()).unwrap();
    join(&alice, &team, &bob, &[]);

    let proposal = bob.engine.leave(team.group_id.clone()).unwrap();
    let outcomes = alice.engine.ingest(vec![proposal]).unwrap();
    let event = outcomes
        .iter()
        .find_map(|o| match o {
            IngestOutcome::PublishRequired { event, .. } => Some(event.clone()),
            _ => None,
        })
        .unwrap_or_else(|| panic!("kein automatischer Commit: {outcomes:?}"));
    alice.engine.confirm_published(team.group_id.clone()).unwrap();
    bob.engine.ingest(vec![event]).unwrap();
    let alice_view = alice.engine.team(team.group_id.clone()).unwrap().unwrap();
    assert_eq!(alice_view.members, vec![alice.pubkey.clone()]);
}

#[test]
fn admin_rechte_weitergeben() {
    let dir = TempDir::new().unwrap();
    let alice = device(&dir, "alice", 9);
    let bob = device(&dir, "bob", 10);
    let team = alice.engine.create_team("Team".into(), relays()).unwrap();
    join(&alice, &team, &bob, &[]);

    let commit = alice.engine.set_admins(team.group_id.clone(), vec![alice.pubkey.clone(), bob.pubkey.clone()]).unwrap();
    alice.engine.confirm_published(team.group_id.clone()).unwrap();
    bob.engine.ingest(vec![commit]).unwrap();
    let bob_view = bob.engine.team(team.group_id.clone()).unwrap().unwrap();
    let mut expected = vec![alice.pubkey.clone(), bob.pubkey.clone()];
    expected.sort();
    assert_eq!(bob_view.admins, expected);

    // Bob kann jetzt selbst einladen.
    let carol = device(&dir, "carol", 11);
    join(&bob, &team, &carol, &[&alice]);
    assert_eq!(alice.engine.team(team.group_id.clone()).unwrap().unwrap().members.len(), 3);
}

#[test]
fn beschreibung_aendern_nur_admins() {
    let dir = TempDir::new().unwrap();
    let alice = device(&dir, "alice", 36);
    let bob = device(&dir, "bob", 37);
    let team = alice.engine.create_team("Team".into(), relays()).unwrap();
    assert_eq!(team.description, "");
    join(&alice, &team, &bob, &[]);

    // Alice (Admin) sperrt den Plan: Die Beschreibung kommt mit dem Commit bei Bob an.
    let lock = r#"{"v":1,"lock":{"seg":[{"since":5}],"admins":["0123456789abcdef"]}}"#.to_string();
    let commit = alice.engine.update_team(team.group_id.clone(), None, Some(lock.clone())).unwrap();
    alice.engine.confirm_published(team.group_id.clone()).unwrap();
    bob.engine.ingest(vec![commit]).unwrap();
    let bob_view = bob.engine.team(team.group_id.clone()).unwrap().unwrap();
    assert_eq!(bob_view.description, lock);
    assert_eq!(bob_view.admins, vec![alice.pubkey.clone()]);

    // Bob ist kein Admin: Er kann die Beschreibung nicht ändern.
    assert!(bob.engine.update_team(team.group_id.clone(), None, Some(String::new())).is_err());

    // Admins und Beschreibung in einem Commit; Grenzen der Eingabe.
    let both = alice
        .engine
        .update_team(team.group_id.clone(), Some(vec![alice.pubkey.clone(), bob.pubkey.clone()]), Some(String::new()))
        .unwrap();
    alice.engine.confirm_published(team.group_id.clone()).unwrap();
    bob.engine.ingest(vec![both]).unwrap();
    let bob_view = bob.engine.team(team.group_id.clone()).unwrap().unwrap();
    assert_eq!(bob_view.description, "");
    assert_eq!(bob_view.admins.len(), 2);
    assert!(matches!(
        alice.engine.update_team(team.group_id.clone(), None, Some("x".repeat(4097))),
        Err(MlsError::InvalidInput { .. })
    ));
    assert!(matches!(alice.engine.update_team(team.group_id.clone(), None, None), Err(MlsError::InvalidInput { .. })));
}

#[test]
fn zu_frueh_eingetroffene_nachricht_wird_nachgeholt() {
    let dir = TempDir::new().unwrap();
    let alice = device(&dir, "alice", 12);
    let bob = device(&dir, "bob", 13);
    let team = alice.engine.create_team("Team".into(), relays()).unwrap();
    join(&alice, &team, &bob, &[]);

    let commit = bob.engine.self_update(team.group_id.clone()).unwrap();
    bob.engine.confirm_published(team.group_id.clone()).unwrap();
    let early = bob.engine.encrypt(team.group_id.clone(), 30078, "früh".into()).unwrap();

    let first = alice.engine.ingest(vec![early]).unwrap();
    assert!(matches!(first.as_slice(), [IngestOutcome::Deferred { .. }]), "{first:?}");
    assert_eq!(alice.engine.deferred_count().unwrap(), 1);

    let later = alice.engine.ingest(vec![commit]).unwrap();
    assert!(later.iter().any(|o| matches!(o, IngestOutcome::GroupChanged { .. })), "{later:?}");
    assert_eq!(app_messages(&later), vec![(bob.pubkey.clone(), "früh".to_string())]);
    assert_eq!(alice.engine.deferred_count().unwrap(), 0);
}

#[test]
fn reihenfolge_im_stapel_spielt_keine_rolle() {
    let dir = TempDir::new().unwrap();
    let alice = device(&dir, "alice", 14);
    let bob = device(&dir, "bob", 15);
    let team = alice.engine.create_team("Team".into(), relays()).unwrap();
    join(&alice, &team, &bob, &[]);

    let commit = bob.engine.self_update(team.group_id.clone()).unwrap();
    bob.engine.confirm_published(team.group_id.clone()).unwrap();
    let message = bob.engine.encrypt(team.group_id.clone(), 30078, "nach dem Commit".into()).unwrap();
    // Relays liefern oft die neuesten Events zuerst.
    let outcomes = alice.engine.ingest(vec![message, commit]).unwrap();
    assert_eq!(app_messages(&outcomes), vec![(bob.pubkey.clone(), "nach dem Commit".to_string())]);
}

#[test]
fn datenbank_bleibt_nach_neustart_und_braucht_den_richtigen_schluessel() {
    let dir = TempDir::new().unwrap();
    let alice = device(&dir, "alice", 16);
    let team = alice.engine.create_team("Team".into(), relays()).unwrap();
    let (path, key, secret) = (alice.path.clone(), alice.db_key.clone(), alice.secret.clone());
    drop(alice);

    let reopened = MlsEngine::open(path.clone(), key, secret.clone()).unwrap();
    assert_eq!(reopened.teams().unwrap(), vec![reopened.team(team.group_id.clone()).unwrap().unwrap()]);
    drop(reopened);

    let wrong = MlsEngine::open(path, vec![0x55; 32], secret);
    assert!(matches!(wrong, Err(MlsError::Storage { .. })));
}

#[test]
fn fremde_und_kaputte_eingaben() {
    let dir = TempDir::new().unwrap();
    let alice = device(&dir, "alice", 17);
    let bob = device(&dir, "bob", 18);
    let carol = device(&dir, "carol", 19);
    let team = alice.engine.create_team("Team".into(), relays()).unwrap();

    assert!(matches!(alice.engine.encrypt("zz".into(), 1, "x".into()), Err(MlsError::InvalidInput { .. })));
    assert!(matches!(alice.engine.invite(team.group_id.clone(), "{}".into()), Err(MlsError::InvalidInput { .. })));
    assert!(matches!(alice.engine.create_team("x".into(), vec!["http://relay.example".into()]), Err(MlsError::InvalidInput { .. })));
    assert!(matches!(
        MlsEngine::open(dir.path().join("x.db").to_string_lossy().into_owned(), vec![1; 31], secret(20)),
        Err(MlsError::InvalidInput { .. })
    ));

    // Einladung für Bob ist für Carol unlesbar.
    let key_package = bob.engine.key_package_event(relays()).unwrap();
    let invitation = alice.engine.invite(team.group_id.clone(), key_package).unwrap();
    assert_eq!(carol.engine.receive_invite(invitation.welcome_events[0].clone()).unwrap(), None);

    // Gruppen-Event einer fremden Gruppe und Müll werden verworfen, nicht zurückgestellt.
    let foreign = alice.engine.encrypt(team.group_id.clone(), 30078, "x".into()).unwrap();
    let outcomes = carol.engine.ingest(vec![foreign, "kein json".into()]).unwrap();
    assert_eq!(outcomes.len(), 2);
    assert!(outcomes.iter().all(|o| matches!(o, IngestOutcome::Ignored { .. })), "{outcomes:?}");
    assert_eq!(carol.engine.deferred_count().unwrap(), 0);
}

#[test]
fn identitaetsschluessel_pruefen() {
    assert!(is_valid_identity_secret(secret(1)));
    assert!(!is_valid_identity_secret(vec![0; 32]));
    assert!(!is_valid_identity_secret(vec![0xff; 32]));
    assert!(!is_valid_identity_secret(vec![1; 16]));
    assert_eq!(public_key_of(secret(1)).unwrap().len(), 64);
}

#[test]
fn offene_einladung_bleibt_nach_neustart_erhalten() {
    let dir = TempDir::new().unwrap();
    let alice = device(&dir, "alice", 23);
    let bob = device(&dir, "bob", 24);
    let team = alice.engine.create_team("Küche".into(), relays()).unwrap();
    let invitation = alice.engine.invite(team.group_id.clone(), bob.engine.key_package_event(relays()).unwrap()).unwrap();
    alice.engine.confirm_published(team.group_id.clone()).unwrap();
    let invite = bob.engine.receive_invite(invitation.welcome_events[0].clone()).unwrap().unwrap();
    let now = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).unwrap().as_secs();
    assert!(invite.created_at <= now + 5 && invite.created_at + 600 >= now, "{}", invite.created_at);

    let (path, key, secret) = (bob.path.clone(), bob.db_key.clone(), bob.secret.clone());
    drop(bob);
    let reopened = MlsEngine::open(path, key, secret).unwrap();
    assert_eq!(reopened.pending_invites().unwrap(), vec![invite.clone()]);
    reopened.accept_invite(invite.invite_id).unwrap();
    assert!(reopened.pending_invites().unwrap().is_empty());
}

#[test]
fn signiert_nur_loeschanfragen_und_anmeldungen() {
    use nostr::{Event, JsonUtil};
    let dir = TempDir::new().unwrap();
    let alice = device(&dir, "alice", 25);
    let deletion = alice
        .engine
        .sign_event(5, vec![vec!["e".into(), "ab".repeat(32)], vec!["k".into(), "30443".into()]], String::new())
        .unwrap();
    let event = Event::from_json(&deletion).unwrap();
    event.verify().unwrap();
    assert_eq!(event.pubkey.to_hex(), alice.pubkey);
    assert_eq!(event.kind.as_u16(), 5);

    let auth = alice
        .engine
        .sign_event(22242, vec![vec!["relay".into(), RELAYS[0].into()], vec!["challenge".into(), "x".into()]], String::new())
        .unwrap();
    Event::from_json(&auth).unwrap().verify().unwrap();

    assert!(matches!(alice.engine.sign_event(1, vec![], "hallo".into()), Err(MlsError::InvalidInput { .. })));
    assert!(matches!(alice.engine.sign_event(445, vec![], String::new()), Err(MlsError::InvalidInput { .. })));
    assert!(matches!(alice.engine.sign_event(5, vec![vec![]], String::new()), Err(MlsError::InvalidInput { .. })));
}

#[test]
fn wettlauf_zweier_commits_endet_ueberall_gleich() {
    use nostr::{Event, JsonUtil};
    let dir = TempDir::new().unwrap();
    let alice = device(&dir, "alice", 26);
    let bob = device(&dir, "bob", 27);
    let carol = device(&dir, "carol", 28);
    let team = alice.engine.create_team("Team".into(), relays()).unwrap();
    join(&alice, &team, &bob, &[]);
    let admins = alice.engine.set_admins(team.group_id.clone(), vec![alice.pubkey.clone(), bob.pubkey.clone()]).unwrap();
    alice.engine.confirm_published(team.group_id.clone()).unwrap();
    bob.engine.ingest(vec![admins]).unwrap();
    join(&alice, &team, &carol, &[&bob]);

    // Alice und Bob ändern gleichzeitig dieselbe Epoche.
    let from_alice = alice.engine.self_update(team.group_id.clone()).unwrap();
    let from_bob = bob.engine.self_update(team.group_id.clone()).unwrap();
    let key = |json: &str| {
        let event = Event::from_json(json).unwrap();
        (event.created_at, event.id)
    };
    let (better, worse) = if key(&from_alice) < key(&from_bob) { (from_alice, from_bob) } else { (from_bob, from_alice) };

    // Carol sieht zuerst den schlechteren Commit, danach den besseren (MIP-03: früher gewinnt).
    carol.engine.ingest(vec![worse]).unwrap();
    let outcomes = carol.engine.ingest(vec![better.clone()]).unwrap();
    assert!(outcomes.iter().any(|o| matches!(o, IngestOutcome::RolledBack { .. })), "{outcomes:?}");
    assert!(outcomes.iter().any(|o| matches!(o, IngestOutcome::GroupChanged { .. })), "{outcomes:?}");
}

#[test]
fn austritt_haelt_den_rest_des_stapels_bis_zur_bestaetigung_an() {
    let dir = TempDir::new().unwrap();
    let alice = device(&dir, "alice", 29);
    let bob = device(&dir, "bob", 30);
    let carol = device(&dir, "carol", 31);
    let team = alice.engine.create_team("Team".into(), relays()).unwrap();
    join(&alice, &team, &bob, &[]);
    join(&alice, &team, &carol, &[&bob]);

    let proposal = bob.engine.leave(team.group_id.clone()).unwrap();
    // Eine Sekunde später, damit die Nachricht im Stapel sicher nach dem Vorschlag kommt.
    std::thread::sleep(std::time::Duration::from_millis(1100));
    let later = carol.engine.encrypt(team.group_id.clone(), 30078, "danach".into()).unwrap();
    let outcomes = alice.engine.ingest(vec![proposal, later]).unwrap();
    assert!(
        matches!(outcomes.as_slice(), [IngestOutcome::PublishRequired { .. }, IngestOutcome::Deferred { .. }]),
        "{outcomes:?}"
    );
    assert!(app_messages(&outcomes).is_empty(), "{outcomes:?}");
    assert_eq!(alice.engine.deferred_count().unwrap(), 1);

    let after = alice.engine.confirm_published(team.group_id.clone()).unwrap();
    assert_eq!(app_messages(&after), vec![(carol.pubkey.clone(), "danach".to_string())], "{after:?}");
    assert_eq!(alice.engine.deferred_count().unwrap(), 0);
}

#[test]
fn commit_laesst_sich_vor_dem_verarbeiten_erkennen() {
    let dir = TempDir::new().unwrap();
    let alice = device(&dir, "alice", 32);
    let bob = device(&dir, "bob", 33);
    let team = alice.engine.create_team("Team".into(), relays()).unwrap();
    let bob_team = join(&alice, &team, &bob, &[]);

    let commit = bob.engine.self_update(team.group_id.clone()).unwrap();
    // Noch nicht übernommen: Alice sieht einen Commit für ihre aktuelle Epoche.
    assert_eq!(alice.engine.peek_commit(commit.clone()).unwrap(), Some(bob_team.epoch));
    bob.engine.confirm_published(team.group_id.clone()).unwrap();
    let message = bob.engine.encrypt(team.group_id.clone(), 30078, "x".into()).unwrap();
    // Anwendungsnachrichten und Unlesbares sind keine Commits.
    assert_eq!(alice.engine.ingest(vec![commit.clone()]).unwrap().len(), 1);
    assert_eq!(alice.engine.peek_commit(message).unwrap(), None);
    assert_eq!(alice.engine.peek_commit("kein json".into()).unwrap(), None);
    // Nach dem Übernehmen bleibt er über die gespeicherte Epoche erkennbar.
    assert_eq!(alice.engine.peek_commit(commit).unwrap(), Some(bob_team.epoch));
}

/// Hält fest, warum die App eigene Commits erst nach einer Wartezeit übernimmt: Die verwendete
/// MDK-Version setzt einen bereits übernommenen eigenen Commit nicht zurück, wenn ein früherer
/// Commit derselben Epoche eintrifft (kein Wiederherstellungspunkt). Ändert sich das mit einer
/// neueren MDK-Version, schlägt dieser Test fehl und die Wartezeit kann überdacht werden.
#[test]
fn eigene_commits_werden_bei_einem_wettlauf_nicht_zurueckgesetzt() {
    use nostr::{Event, JsonUtil};
    let dir = TempDir::new().unwrap();
    let alice = device(&dir, "alice", 34);
    let bob = device(&dir, "bob", 35);
    let team = alice.engine.create_team("Team".into(), relays()).unwrap();
    let start = join(&alice, &team, &bob, &[]);

    let from_alice = alice.engine.self_update(team.group_id.clone()).unwrap();
    let from_bob = bob.engine.self_update(team.group_id.clone()).unwrap();
    alice.engine.confirm_published(team.group_id.clone()).unwrap();
    bob.engine.confirm_published(team.group_id.clone()).unwrap();
    // Beide Commits gehören zur selben Epoche ...
    assert_eq!(alice.engine.peek_commit(from_bob.clone()).unwrap(), Some(start.epoch));
    assert_eq!(bob.engine.peek_commit(from_alice.clone()).unwrap(), Some(start.epoch));

    let key = |json: &str| {
        let event = Event::from_json(json).unwrap();
        (event.created_at, event.id)
    };
    let (loser, winner_event) = if key(&from_alice) < key(&from_bob) { (&bob, from_alice) } else { (&alice, from_bob) };
    // ... und das Gerät mit dem unterlegenen Commit setzt nicht zurück.
    let outcomes = loser.engine.ingest(vec![winner_event]).unwrap();
    assert!(!outcomes.iter().any(|o| matches!(o, IngestOutcome::RolledBack { .. })), "{outcomes:?}");
    assert!(outcomes.iter().all(|o| matches!(o, IngestOutcome::Ignored { .. })), "{outcomes:?}");
}

import SwiftUI

struct ProfilesView: View {
    let onOpen: (Profile) -> Void
    @State private var profiles: [Profile] = Prefs.shared.profiles
    @State private var showAdd = false
    @State private var editing: Profile?
    @State private var toDelete: Profile?
    @State private var passwordFor: (Profile, String)?
    @State private var passwordText = ""
    @State private var wrongPassword = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 18) {
                    HStack(spacing: 12) {
                        AppLogo(size: 44)
                        VStack(alignment: .leading, spacing: 2) {
                            Text("STREAMBOX").font(.caption.weight(.bold)).tracking(3).foregroundColor(Theme.accent2)
                            Text("Who's watching?").font(.largeTitle.weight(.heavy)).foregroundColor(Theme.text)
                        }
                    }
                    .padding(.top, 24)

                    if profiles.isEmpty {
                        Text("No profiles yet. Add your provider's Xtream Codes login or an M3U playlist URL to begin.")
                            .foregroundColor(Theme.dim)
                            .padding(.vertical, 30)
                    }

                    ForEach(profiles) { p in
                        Button {
                            if Prefs.shared.isProtected(p) && !Prefs.shared.protectedUnlocked { ask(p, "open") } else { onOpen(p) }
                        } label: { ProfileRow(profile: p) }
                            .buttonStyle(.plain)
                            .contextMenu {
                                Button { Prefs.shared.isProtected(p) ? ask(p, "edit") : (editing = p) } label: { Label("Edit", systemImage: "pencil") }
                                Button(role: .destructive) { Prefs.shared.isProtected(p) ? ask(p, "delete") : (toDelete = p) } label: { Label("Delete", systemImage: "trash") }
                            }
                    }

                    Button { showAdd = true } label: {
                        HStack {
                            Image(systemName: "plus.circle.fill").font(.title2)
                            Text("Add profile").font(.headline)
                        }
                        .frame(maxWidth: .infinity)
                        .padding(.vertical, 18)
                        .foregroundColor(Theme.text)
                        .background(RoundedRectangle(cornerRadius: 18).strokeBorder(style: StrokeStyle(lineWidth: 1.2, dash: [6, 5])).foregroundColor(Theme.stroke))
                    }

                    Text("Long-press a profile to edit or delete it. This app is a player only and contains no channels.")
                        .font(.footnote)
                        .foregroundColor(Theme.dim)
                        .padding(.top, 8)
                }
                .padding(.horizontal, 22)
                .frame(maxWidth: 640)
                .frame(maxWidth: .infinity)
            }
            .background(AppBackground())
            .sheet(isPresented: $showAdd, onDismiss: reload) { AddProfileView(existing: nil) }
            .sheet(item: $editing, onDismiss: reload) { AddProfileView(existing: $0) }
            .alert("Profile password", isPresented: Binding(get: { passwordFor != nil }, set: { if !$0 { passwordFor = nil } })) {
                SecureField("Password", text: $passwordText).keyboardType(.numberPad)
                Button("OK") { checkPassword() }
                Button("Cancel", role: .cancel) { passwordFor = nil }
            }
            .alert("Wrong password", isPresented: $wrongPassword) { Button("OK", role: .cancel) {} }
            .confirmationDialog("Delete \(toDelete?.name ?? "")?", isPresented: Binding(get: { toDelete != nil }, set: { if !$0 { toDelete = nil } }), titleVisibility: .visible) {
                Button("Delete", role: .destructive) {
                    if let p = toDelete { Prefs.shared.delete(p) }
                    reload()
                }
            }
        }
    }

    private func reload() { profiles = Prefs.shared.profiles }

    private func ask(_ p: Profile, _ action: String) {
        passwordText = ""
        passwordFor = (p, action)
    }

    private func checkPassword() {
        guard let pending = passwordFor else { return }
        let (p, action) = pending
        passwordFor = nil
        guard Secret.checkPassword(passwordText) else { wrongPassword = true; return }
        Prefs.shared.protectedUnlocked = true
        switch action {
        case "edit": editing = p
        case "delete": toDelete = p
        default: onOpen(p)
        }
    }
}

private struct ProfileRow: View {
    let profile: Profile

    var body: some View {
        HStack(spacing: 14) {
            ZStack {
                Circle().fill(LinearGradient(colors: [Theme.accent, Theme.accent2], startPoint: .topLeading, endPoint: .bottomTrailing))
                Text(String(profile.name.prefix(1)).uppercased())
                    .font(.title2.weight(.heavy))
                    .foregroundColor(.white)
            }
            .frame(width: 54, height: 54)
            VStack(alignment: .leading, spacing: 4) {
                Text(profile.name).font(.headline).foregroundColor(Theme.text)
                Text(Prefs.shared.isProtected(profile) ? "Built-in profile  •  🔒 login hidden"
                     : profile.isXtream ? "Xtream Codes  •  \(profile.username)" : "M3U playlist")
                    .font(.subheadline).foregroundColor(Theme.dim).lineLimit(1)
            }
            Spacer()
            Image(systemName: "chevron.right").foregroundColor(Theme.dim)
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(Theme.glass))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).stroke(Theme.stroke, lineWidth: 0.5))
    }
}

struct AddProfileView: View {
    let existing: Profile?
    @Environment(\.dismiss) private var dismiss
    @State private var isXtream = true
    @State private var name = ""
    @State private var server = ""
    @State private var username = ""
    @State private var password = ""
    @State private var m3u = ""
    @State private var checking = false
    @State private var error = ""

    var body: some View {
        NavigationStack {
            Form {
                Picker("Type", selection: $isXtream) {
                    Text("Xtream Codes").tag(true)
                    Text("M3U playlist").tag(false)
                }
                .pickerStyle(.segmented)
                .listRowBackground(Color.clear)

                Section {
                    TextField("Profile name", text: $name)
                    if isXtream {
                        TextField("Server URL (http://host:port)", text: $server)
                            .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                        TextField("Username", text: $username)
                            .textInputAutocapitalization(.never).autocorrectionDisabled()
                        SecureField("Password", text: $password)
                    } else {
                        TextField("Playlist URL (.m3u)", text: $m3u)
                            .keyboardType(.URL).textInputAutocapitalization(.never).autocorrectionDisabled()
                    }
                }
                if !error.isEmpty {
                    Section { Text(error).foregroundColor(Theme.live) }
                }
            }
            .scrollContentBackground(.hidden)
            .background(AppBackground())
            .navigationTitle(existing == nil ? "Add profile" : "Edit profile")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    if checking { ProgressView() } else { Button("Save") { Task { await save() } }.bold() }
                }
            }
            .onAppear {
                guard let p = existing else { return }
                isXtream = p.isXtream
                name = p.name
                if !Prefs.shared.isProtected(p) {
                    server = p.server; username = p.username; password = p.password; m3u = p.m3uUrl
                }
            }
        }
    }

    private func save() async {
        error = ""
        var p = Profile(id: existing?.id ?? UUID().uuidString,
                        name: name.trimmingCharacters(in: .whitespaces),
                        type: isXtream ? Profile.xtream : Profile.m3u)
        if let old = existing, Prefs.shared.isProtected(old), server.isEmpty, username.isEmpty, password.isEmpty {
            // Hidden login left empty: only the name changes.
            var keep = old
            keep.name = p.name.isEmpty ? old.name : p.name
            Prefs.shared.save(keep)
            dismiss()
            return
        }
        if isXtream {
            guard !server.isEmpty, !username.isEmpty, !password.isEmpty else { error = "Fill in server, username and password"; return }
            p.server = Catalog.normalizeServer(server)
            p.username = username.trimmingCharacters(in: .whitespaces)
            p.password = password.trimmingCharacters(in: .whitespaces)
            if p.name.isEmpty { p.name = URL(string: p.server)?.host ?? "IPTV" }
            checking = true
            defer { checking = false }
            do {
                _ = try await Catalog.authenticate(p)
            } catch {
                self.error = "Login failed: \(Net.readable(error))"
                return
            }
        } else {
            guard URL(string: m3u.trimmingCharacters(in: .whitespaces)) != nil, !m3u.isEmpty else { error = "Enter a valid playlist URL"; return }
            p.m3uUrl = m3u.trimmingCharacters(in: .whitespaces)
            if p.name.isEmpty { p.name = "Playlist" }
        }
        Prefs.shared.save(p)
        dismiss()
    }
}

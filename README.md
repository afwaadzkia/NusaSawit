# NusaSawit

Plugin perkebunan kelapa sawit modern untuk **NusaSpigot / Paper 1.21.11** (Java 21) dengan dukungan penuh model pohon 3D realistis untuk **Nexo**, **ItemsAdder**, maupun **CraftEngine** (otomatis terdeteksi), serta sistem ekonomi lengkap berbasis **Vault**.

![Preview Pohon Sawit 3D](docs/nusasawit_3d_palm_preview.png)

![Preview Tekstur Custom NusaSawit](docs/nusasawit_textures_preview.png)

---

## Fitur Utama

### 1. Multi-Engine 3D Support: Nexo, ItemsAdder & CraftEngine (+ Fallback Vanilla)
- **Auto-Detect (`pack-provider: AUTO`)**: Otomatis mendeteksi apakah server memakai **Nexo** (1.26+ / 1.30+), **ItemsAdder** (3.6+ / 4.0+), atau **CraftEngine** (0.0.67+ / 26.x+) tanpa konfigurasi rumit.
- **Auto-Sync Resource Pack**: Menyalin seluruh konfigurasi YAML, 16 tekstur PNG pixel-art custom, dan model 3D JSON secara otomatis ke folder plugin resource pack yang terpasang:
  - **Nexo**: `plugins/Nexo/`
  - **ItemsAdder**: `plugins/ItemsAdder/contents/nusasawit/`
  - **CraftEngine**: `plugins/CraftEngine/resources/nusasawit/`
- Bila ketiganya belum terpasang, plugin tetap berjalan mulus menggunakan material vanilla ber-PDC.

### 2. Lisensi Perkebunan Kayu Alami (Anti-Exploit)
- Pemain wajib menebang **20.000 blok kayu alami** (default `OAK_LOG` / `OAK_WOOD`, dapat diubah langsung via GUI Admin) untuk membuka **Lisensi Perkebunan Sawit** dan mendapatkan **1x Bibit Sawit Tenera** gratis.
- Dilengkapi pelacak chunk PDC anti-exploit sehingga blok kayu yang dipasang pemain atau digeser piston tidak dihitung.
- Setiap kelipatan tebangan berikutnya (`10.000` kayu secara default) memberikan hadiah bibit tambahan secara otomatis.

### 3. Pohon Sawit 3D Tinggi Realistis (~6,4 Blok) & Panen Berulang
- **Tahap 1 (`sawit_stage_1`)**: Tunas kelapa sawit di dalam polybag hitam.
- **Tahap 2 (`sawit_stage_2`)**: Pohon sawit muda dengan batang bersisik serat pelepah dan tajuk daun melengkung.
- **Tahap 3 Berbunga (`sawit_stage_3_growing`)**: Pohon kelapa sawit dewasa tinggi (~6,4 blok) dengan tajuk pelepah 3D berlapis dan tandan bunga muda.
- **Tahap 3 Siap Panen (`sawit_stage_3_ripe`)**: Pohon sawit dewasa dengan **Tandan Buah Segar (TBS) merah-oranye** menggantung di bawah tajuk pelepah — dapat dipanen berulang kali tanpa menebang pohon!

### 4. Alat Panen Egrek, Pupuk NPK, Kilang CPO & Koperasi (`/sawit`)
- **Pupuk NPK Sawit (`pupuk_npk`)**: Klik kanan pohon sawit untuk mempercepat waktu tumbuh dan pematangan buah.
- **Egrek Panen Sawit (`egrek_sawit`) & Egrek Baja Nusantara (`egrek_master`)**: Sabit galah khusus panen yang memberikan bonus jumlah TBS serta peluang tinggi mendapatkan **TBS Tenera Super**.
- **Kilang Minyak Sawit (CPO)**: Mengolah TBS biasa menjadi botol **Minyak Sawit Mentah (CPO)** bernilai jual premium.
- **Upgrade Kapasitas Lahan**: Batas slot pohon per pemain yang dapat ditingkatkan menggunakan saldo Vault agar ekonomi dan performa server tetap terjaga.
- **Proteksi Kepemilikan & Klaim**: Pohon terkunci untuk pemiliknya dan terintegrasi dengan plugin proteksi wilayah (WorldGuard, GriefPrevention, Lands, dll.).

### 5. In-Game Admin GUI (`/sawit admin`)
- Ubah harga jual **TBS**, **TBS Super**, **CPO**, biaya olah & rasio **Kilang CPO**, harga toko item, waktu tumbuh tiap tahap, kapasitas slot kebun, hingga **target tebang kayu lisensi** langsung dari dalam game (via tombol klik atau input angka di chat) dan otomatis tersimpan ke `config.yml` tanpa restart.

---

## Instalasi

1. Unduh `NusaSawit-1.1.0.jar` dari halaman **Releases** (atau hasil build di `build/libs/`), lalu masukkan ke folder `plugins/` server **Paper / NusaSpigot 1.21.11** bersama **Vault** (+ plugin ekonomi) serta salah satu dari **Nexo**, **ItemsAdder**, atau **CraftEngine**.
2. Nyalakan server. `NusaSawit` akan otomatis menyalin paket model 3D & tekstur ke plugin resource pack yang aktif:
   - **Pengguna Nexo**: Otomatis tersalin ke `plugins/Nexo/` (aset manual: [`nexo-pack/`](nexo-pack/)). Jalankan:
     ```text
     /nexo reload all
     ```
   - **Pengguna ItemsAdder**: Otomatis tersalin ke `plugins/ItemsAdder/contents/nusasawit/` (aset manual: [`itemsadder-pack/`](itemsadder-pack/)). Jalankan:
     ```text
     /iazip
     ```
   - **Pengguna CraftEngine**: Otomatis tersalin ke `plugins/CraftEngine/resources/nusasawit/` (aset manual: [`craftengine-pack/`](craftengine-pack/)). Jalankan:
     ```text
     /ce reload all
     ```
   - **Pengguna Geyser / Bedrock Edition (Tanpa Ekstensi Tambahan)**: Tersedia paket 3D Bedrock siap pakai di [`bedrock-pack/`](bedrock-pack/) (juga diekspor otomatis ke `plugins/NusaSawit/bedrock-pack/`):
     - Salin `bedrock-pack/NusaSawit_Bedrock.mcpack` ke folder `packs/` Geyser.
     - Salin `bedrock-pack/custom_mappings/nusasawit.json` ke folder `custom_mappings/` Geyser.
     - Pada CraftEngine, konfigurasi otomatis menampilkan `item_display` ke pemain Java dan model 3D `armor_stand` + `.geo.json` attachable ke pemain Bedrock (`is_bedrock_player`).

---

## Perintah & Permission

| Perintah | Deskripsi | Permission |
| :--- | :--- | :--- |
| `/sawit` | Membuka GUI Utama Kebun, Koperasi Jual, Kilang CPO, Upgrade Slot & Toko | `nusasawit.use` |
| `/sawit sell` | Menjual cepat seluruh TBS, TBS Super & CPO di inventory | `nusasawit.use` |
| `/sawit progress` | Menampilkan progres tebang kayu alami & ringkasan kebun | `nusasawit.use` |
| `/sawit claim` | Mengklaim hadiah bibit sawit yang tertunda | `nusasawit.use` |
| `/sawit admin` | Membuka **GUI Pengaturan Admin** (harga TBS/CPO, target kayu, durasi tumbuh, dll.) | `nusasawit.admin` |
| `/sawit admin give <player> <item> [jumlah]` | Memberikan item custom NusaSawit ke pemain | `nusasawit.admin` |
| `/sawit admin setwood <player> <jumlah>` | Mengatur progres tebang kayu alami pemain | `nusasawit.admin` |
| `/sawit admin unlock <player>` | Membuka langsung Lisensi Perkebunan Sawit pemain | `nusasawit.admin` |
| `/sawit admin addslots <player> <jumlah>` | Menambah bonus slot maksimal pohon sawit pemain | `nusasawit.admin` |
| `/sawit admin cleanup` | Membersihkan entitas visual sawit orphan di chunk aktif | `nusasawit.admin` |
| `/sawit admin syncpack` | Menyalin ulang seluruh model 3D & tekstur ke engine aktif | `nusasawit.admin` |
| `/sawit admin syncnexo` | Menyalin ulang seluruh model 3D & tekstur ke `plugins/Nexo/` | `nusasawit.admin` |
| `/sawit admin syncia` | Menyalin ulang seluruh model 3D & tekstur ke `plugins/ItemsAdder/contents/nusasawit/` | `nusasawit.admin` |
| `/sawit admin syncce` | Menyalin ulang seluruh model 3D & tekstur ke `plugins/CraftEngine/resources/nusasawit/` | `nusasawit.admin` |
| `/sawit admin reload` | Memuat ulang konfigurasi `config.yml` | `nusasawit.admin` |

---

## Build dari Source

Membutuhkan **JDK 21**:

```bash
./gradlew build
```

File JAR siap pakai akan dihasilkan di `build/libs/NusaSawit-1.1.0.jar`.

# resource-core

サバイバル・資源・ハブを 1 サーバーに統合した CanvasMC サーバー向けのプラグインです。資源ワールドの作成とリセット、ハブの運用をまとめて行います。

- 資源ワールド（オーバーワールド・ネザー・エンド）を起動時に自動作成
- 資源ワールド同士のポータル連携（サバイバル側には影響なし）
- 管理 GUI またはコマンドからの手動リセット（告知カウントダウン、キャンセル可）
- リセット後の安全対策（古い位置でのログイン、消えたリスポーン地点）
- ハブへの移動と保護
- config を編集しなくてもすべて動作

## 動作環境

| 項目 | バージョン |
|---|---|
| サーバー | CanvasMC 26.3（`canvas-build.1001` で確認） |
| Java | 25 以上 |
| 必須プラグイン | [Worlds](https://modrinth.com/project/gBIw3Gvy) 4.5.0 |

Canvas の `EntityPortalAsyncEvent` はある場合だけ使うので、Folia でも読み込める作りですが、確認しているのは Canvas だけです。

## 導入手順

1. `plugins/` に `worlds-4.5.0-all.jar` と `resource-core-1.0.0.jar` を入れます。
2. サーバーを起動します。`resource:overworld` / `resource:nether` / `resource:the_end` が自動で作成されます。
3. ハブにするワールドに移動して `/resource-core sethub` を実行します（管理 GUI からも設定できます）。

追加のワールドは `world/dimensions/<namespace>/<key>/` に作られます。デフォルトワールド（`minecraft:overworld` など）には触りません。

## コマンドと権限

`/rc` は `/resource-core` の短縮形です。`<ワールド>` は `overworld` / `nether` / `end` です。

| コマンド | 内容 | 権限 | 既定 |
|---|---|---|---|
| `/hub` | ハブへ移動 | `resourcecore.command.hub` | 全員 |
| `/resource [ワールド]` | 資源ワールドのスポーンへ移動（省略時はオーバーワールド） | `resourcecore.command.resource` | 全員 |
| `/resource-core reset <ワールド>` | カウントダウンしてからリセット | `resourcecore.admin.reset` | OP |
| `/resource-core reset <ワールド> --now` | カウントダウンなしで即リセット | `resourcecore.admin.reset` | OP |
| `/resource-core cancel <ワールド>` | カウントダウンを中止 | `resourcecore.admin.cancel` | OP |
| `/resource-core create <ワールド>` | 未作成のワールドを作成 | `resourcecore.admin.create` | OP |
| `/resource-core status` | 状態を表示 | `resourcecore.admin.status` | OP |
| `/resource-core reload` | 設定を再読み込み | `resourcecore.admin.reload` | OP |
| `/resource-core admin` | 管理 GUI を開く | `resourcecore.admin.gui` | OP |
| `/resource-core sethub` | 今いるワールドをハブに設定 | `resourcecore.admin.sethub` | OP |

その他の権限:

- `resourcecore.admin`: 上記の管理権限すべて（既定: OP）
- `resourcecore.hub.bypass`: ハブのブロック保護を無視して建築できる（既定: OP）

`/hub` と `/resource <ワールド>` は確認なしで即実行するので、メニュープラグインからそのまま呼べます。

## 管理 GUI

`/resource-core admin` で開きます。ワールドごとに次の操作ができます。

- 状態の確認（存在、ロード状態、中にいる人数、前回リセット日時）
- 未作成のワールドの作成
- リセット（確認画面で「確定」→ カウントダウン。カウントダウン中はキャンセルボタンに変わります）
- リセット時に seed を変えるかの切り替え
- 各ワールドとハブへの移動、今いるワールドをハブに設定

## リセットの流れ

1. カウントダウンの告知（`--now` なら省略）。この間はキャンセルできます。
2. 対象ワールドへの移動（`/resource`、ポータル、GUI）を止め、「リセット中です」と表示します。
3. 中のプレイヤー全員を `minecraft:overworld` のスポーンへ移動します。移動できない人は `move-timeout-seconds` 経過後にキックします。
4. `reset.backup: true` なら Worlds の API でバックアップを取ります。失敗したらリセットを中止します。
5. Worlds の `regenerate` を実行します。エンドは `ender_dragon_fight.dat` も削除して、ドラゴン戦を初期状態に戻します。
6. スポーンを安全な場所に設定してから、移動のブロックを解除します。

リセット日時は `plugins/resource-core/data.yml` に記録されます。

## 設定（config.yml）

| キー | 既定値 | 内容 |
|---|---|---|
| `worlds.<種類>.key` | `resource:overworld` など | ワールドのキー。3 つとも同じ namespace にしてください（下記の注意を参照） |
| `worlds.<種類>.seed` | 空 | 新規作成時の seed。空ならランダム |
| `worlds.<種類>.change-seed` | `true` | リセットごとに新しいランダム seed にするか（GUI でも変更可） |
| `reset.countdown` | 300, 60, 10 秒 | 告知のタイミング。最大の秒数がカウントダウンの長さ。`message` で文面を個別に指定可。`[]` でカウントダウンなし |
| `reset.backup` | `false` | リセット前にバックアップを取るか |
| `reset.move-timeout-seconds` | `30` | プレイヤー移動の待ち時間 |
| `login.relocate-target` | `hub` | リセット前に資源ワールドでログアウトした人の移動先（`hub` / `resource-spawn`）。ハブ未設定ならメインワールドのスポーン |
| `hub.world` | 空 | ハブのワールドキー。空なら未設定 |
| `hub.teleport-on-join` | `first-join` | ログイン時にハブへ送るタイミング（`first-join` / `always`） |
| `hub.teleport-delay-seconds` | `0` | `/hub` の待機時間 |
| `hub.cancel-on-move` | `true` | 待機中に動いたらキャンセルするか |
| `hub.protection.*` | すべて `true` | `block-break` / `block-place` / `pvp` / `hunger` / `fall-damage` / `mob-spawn` |
| `display.timezone` | `Asia/Tokyo` | 日時表示のタイムゾーン |
| `display.date-format` | `yyyy/MM/dd HH:mm` | 日時表示の書式 |

- 不正な値はエラーログを出して既定値を使います。サーバーは止まりません。
- `hub.protection` の `pvp` / `fall-damage` / `mob-spawn` はハブのゲームルールを変更します。元の値は `data.yml` に残し、OFF にしたときやハブを変えたときに戻します。`mob-spawn` はスポナーなどからの湧きも止めますが、コマンドやスポーンエッグ、プラグインによる召喚は止めません。

### ワールドキーの注意

Worlds は **同じ namespace のワールド同士** を Environment でポータル連携します。そのため:

- 資源ワールドの 3 つのキーは同じ namespace にしてください。
- その namespace に、他のワールドを置かないでください。ハブも別の namespace（例: `lobby:hub`）にしてください。
- 実際の連携先が想定と違うとエラーログを出し、その経路のポータルを無効にします。

メッセージはすべて `messages.yml`（MiniMessage 形式）で変更できます。空文字にすると送信しません。

## ポータルの動作

| 場所 | ポータル | 行き先 | 処理 |
|---|---|---|---|
| 資源オーバーワールド | ネザーゲート | 資源ネザー | Worlds |
| 資源オーバーワールド | エンドポータル | 資源エンド | Worlds |
| 資源ネザー | ネザーゲート | 資源オーバーワールド | Worlds |
| 資源エンド | 帰還ゲート | 資源オーバーワールドのスポーン | resource-core（Worlds だとベッドへ飛ぶため） |
| サバイバル | すべて | 従来どおり | resource-core は関与しない |

Canvas では実際にワールドを移動する直前の `EntityPortalAsyncEvent` でも行き先を確認します。Worlds の [Issue #480](https://github.com/TheNextLvl-net/worlds/issues/480) で報告された、カスタムネザーからデフォルトワールドへ飛ぶ症状への備えです。

## テスト手順

### 自動テスト

```
./gradlew test
```

カウントダウン、ログイン判定、ポータルの判定、スポーン探索、設定の検証を確認します。

### 確認済みの項目

Canvas 26.3 build 1001 + Worlds 4.5.0 のサーバーで、コンソールから次を確認しています。

- 初回起動で 3 ワールドが作成され、再起動後は再作成されずにロードされる
- `reset`（カスタムメッセージ含む）、`cancel`、`--now`、カウントダウン中の `--now` による省略、二重実行の拒否
- 未編集の config での新規起動
- リセットで seed が変わり、スポーンが設定される。`change-seed: false` なら seed を維持する
- `reset.backup: true` でバックアップが作成される（サーバーフォルダの `backups/<namespace>/<key>/` に zip）
- `dragon_killed=1` の状態からのリセットで `ender_dragon_fight.dat` が初期状態に戻る（Worlds 単体では残ることも確認）
- サバイバル側のスポーンが変わらない
- ハブのゲームルールの適用と、OFF にしたときの復元
- 不正な設定値のエラーログと既定値への復帰、namespace 不一致でのポータル無効化

### ゲーム内で確認してほしい項目

**以下はプレイヤー接続が必要なので、まだ一度も動かしていません。** 本番に入れる前にテストサーバーで確認してください。

ワールド移動:

- [ ] `/resource overworld`、`/resource nether`、`/resource end` で各スポーンへ移動できる。エンドでは黒曜石の足場の上に出る
- [ ] `/hub` でハブへ移動できる。`teleport-delay-seconds: 3` にすると待機し、動くとキャンセルされる

ポータル:

- [ ] 資源オーバーワールドのネザーゲート → 資源ネザー、戻ると資源オーバーワールド
- [ ] 資源オーバーワールドのエンドポータル → 資源エンド
- [ ] サバイバルのネザーゲートとエンドポータルが従来どおり動く

ドラゴン戦（資源エンド）:

- [ ] ドラゴンが出現し、討伐できる
- [ ] 討伐後に帰還ゲートが開き、資源オーバーワールドへ戻れる（ベッドを置いていても）
- [ ] ゲートウェイが生成され、通過できる（エンダーパールの投げ込みは Folia の [Issue #51](https://github.com/PaperMC/Folia/issues/51) が未解決）
- [ ] 討伐後にリセットすると、ドラゴンと帰還ゲートが元に戻る
- [ ] ドラゴン戦の途中でリセットしてもボスバーが残らない

リセット:

- [ ] 中にいるプレイヤーがメインワールドのスポーンへ移動する
- [ ] 処理中に `/resource` とポータルが止まり、「リセット中です」と表示される

リセット後の安全対策:

- [ ] 資源ワールドでログアウト → リセット → ログインで、ハブ（または設定した移動先）へ送られる
- [ ] リセット後に資源ワールドでログアウトした人は、再ログインしても移動しない
- [ ] 資源ワールドのベッドでリスポーン地点を設定 → リセット → 死亡で、サバイバルのスポーンでリスポーンする

ハブと GUI:

- [ ] 初回ログインでハブへ送られる。`always` なら毎回送られる
- [ ] ハブでブロックの破壊と設置、PvP、空腹、落下ダメージ、Mob の湧きが止まる（`resourcecore.hub.bypass` があれば建築は可能）
- [ ] 管理 GUI の全ボタンが動く。アイテムを持ち出せない

## 既知の制限

- **Worlds は Canvas を公式サポートしていません。** 作者は「Folia と Paper のみサポート」としています。Canvas の更新で動かなくなる可能性があるので、Canvas を更新するときはテストサーバーで確認してください。
- **Worlds の `regenerate` はファイルの削除に失敗しても警告ログだけで続行します。** リセット後に地形が変わらないときは、Worlds のログを確認してください。
- **ファイル削除はグローバルリージョンで同期実行されます。** 大きなワールドのリセット中は、グローバルリージョンの処理が一時的に止まる可能性があります。
- **プレイヤー以外のエンティティのポータル通過は Worlds に任せています。** Canvas 26.2 で Worlds の [Issue #479](https://github.com/TheNextLvl-net/worlds/issues/479) のエラーが報告されています（プレイヤーには影響しない報告内容です）。
- `/reload` やプラグインの再読み込みには対応していません。設定の反映は `/resource-core reload` を使ってください。カウントダウン中にサーバーを止めると、そのリセットは取り消されます。

## ビルド

Java 25 が必要です。

```
./gradlew build
```

`build/libs/resource-core-1.0.0.jar` が生成されます（Kotlin 標準ライブラリを同梱）。

# DailyPass 乗車レベル計算機

[計算機を開く](https://lencie040.github.io/dailypass-xp-calculator/)

## 自動更新

1. [DailyPass XP Link 1.0.0](https://github.com/lencie040/dailypass-xp-calculator/releases/download/v1.0.0/dailypass-xp-link-1.0.0.jar) を、DailyPass 1.39.0 と Fabric API を入れた Minecraft 1.19.2 クライアントの `mods` に追加します。
2. Minecraft を再起動し、DailyPass が動いているサーバーに入ります。
3. 同じPCで計算機を開いて、目標レベルを入力します。Chromeで初めて開くときは「Minecraft と接続」を押して、ローカルネットワークへのアクセスを許可します。

連携MODはサーバーに追加する必要がありません。DailyPassの乗車レベル画面と同じ問い合わせで、自分の総合レベルとXPを読み取ります。ブラウザには同じPCの `127.0.0.1` 経由で渡し、GitHubは計算機のファイルを配信します。

ブラウザで接続できない場合は、Minecraftの起動中に [ローカル版](http://127.0.0.1:38767/) を開けます。手動入力も使えます。

今のレベルの必要XPにはサーバーの値を使います。以降のレベルと距離は DailyPass の標準設定で計算します。距離に応じてXPが付く場合の換算は1 kmあたり5 XPで、精算ごとに端数が切り捨てられます。

## ビルド

JDK 17 で `./gradlew build` を実行すると、`build/libs/dailypass-xp-link-1.0.0.jar` が生成されます。

cat > app/proguard-rules.pro << 'EOF'
-keep class io.socket.** { *; }
-keep class okhttp3.** { *; }
-keep class okio.** { *; }
-keep class org.json.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**
-dontwarn org.slf4j.**
EOF

echo "═══ gradle files done ═══"

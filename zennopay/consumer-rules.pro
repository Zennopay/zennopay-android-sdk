# Consumer ProGuard rules for the Zennopay SDK.
# Keep the public API surface so partner apps can call it after minification.

-keep public class com.zennopay.sdk.Zennopay { public *; }
-keep public class com.zennopay.sdk.PaymentResult { *; }
-keep public class com.zennopay.sdk.PaymentResult$* { *; }
-keep public class com.zennopay.sdk.PaymentStatus { *; }
-keep public class com.zennopay.sdk.ZennopayError { *; }
-keep public class com.zennopay.sdk.ZennopayError$* { *; }
-keep public class com.zennopay.sdk.ZennopayCallbackActivity { *; }

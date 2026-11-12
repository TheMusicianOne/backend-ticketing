import os


class Settings:
    api_key: str = os.environ.get("FAKE_PSP_API_KEY", "test_fake_psp_key")
    webhook_secret: str = os.environ.get("FAKE_PSP_WEBHOOK_SECRET", "whsec_local_dev_secret")
    public_base_url: str = os.environ.get("FAKE_PSP_PUBLIC_BASE_URL", "http://localhost:8081")


settings = Settings()
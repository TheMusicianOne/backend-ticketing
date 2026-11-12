from fastapi import FastAPI

app = FastAPI(title="Fake Payment Provider")


@app.get("/health")
def health():
    return {"status": "UP"}
import uuid
from datetime import datetime
from typing import List, Optional
from .base import BaseStorageProvider, RemoteFile

class DummyStorageProvider(BaseStorageProvider):
    """
    In-memory storage provider for testing.
    """
    def __init__(self, config: dict):
        self.config = config
        # Mock Data
        self._files = [
            RemoteFile(
                id="file_1",
                name="Project Proposal.pdf",
                mime_type="application/pdf",
                size=1024 * 500, # 500KB
                modified_at=datetime.now(),
                webview_link="http://localhost:8000/dummy/file_1"
            ),
            RemoteFile(
                id="file_2",
                name="Budget 2025.xlsx",
                mime_type="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                size=1024 * 20, # 20KB
                modified_at=datetime.now(),
                webview_link="http://localhost:8000/dummy/file_2"
            )
        ]

    def list_files(self, folder_id: Optional[str] = None) -> List[RemoteFile]:
        return self._files

    def upload_file(self, name: str, content: bytes, folder_id: Optional[str] = None) -> RemoteFile:
        new_file = RemoteFile(
            id=str(uuid.uuid4()),
            name=name,
            mime_type="application/octet-stream",
            size=len(content),
            modified_at=datetime.now(),
            webview_link=f"http://localhost:8000/dummy/new_{name}"
        )
        self._files.append(new_file)
        return new_file

    def delete_file(self, file_id: str) -> bool:
        initial_len = len(self._files)
        self._files = [f for f in self._files if f.id != file_id]
        return len(self._files) < initial_len

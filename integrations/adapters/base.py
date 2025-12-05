from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import List, Optional
from datetime import datetime

@dataclass
class RemoteFile:
    id: str
    name: str
    mime_type: str
    size: int
    modified_at: datetime
    webview_link: str

class BaseStorageProvider(ABC):
    """
    Abstract base class for storage providers (e.g., Google Drive, Infomaniak kDrive).
    """
    
    @abstractmethod
    def list_files(self, folder_id: Optional[str] = None) -> List[RemoteFile]:
        """List files in a folder."""
        pass

    @abstractmethod
    def upload_file(self, name: str, content: bytes, folder_id: Optional[str] = None) -> RemoteFile:
        """Upload a file."""
        pass

    @abstractmethod
    def delete_file(self, file_id: str) -> bool:
        """Delete a file."""
        pass

class BaseCalendarProvider(ABC):
    """
    Abstract base class for calendar providers.
    """
    # Placeholder for future implementation
    pass

class BaseChatProvider(ABC):
    """
    Abstract base class for chat providers (e.g., kChat, Slack).
    """
    @abstractmethod
    def get_teams(self) -> List[dict]:
        pass

    @abstractmethod
    def get_channels(self, team_id: str) -> List[dict]:
        pass

    @abstractmethod
    def post_message(self, channel_id: str, message: str) -> dict:
        pass

class BaseMeetingProvider(ABC):
    """
    Abstract base class for meeting providers (e.g., kMeet, Zoom).
    """
    @abstractmethod
    def create_meeting(self, subject: str) -> dict:
        pass

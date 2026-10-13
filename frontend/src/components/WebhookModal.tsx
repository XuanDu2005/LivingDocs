import React, { useState, useEffect } from 'react';
import { useTranslation } from 'react-i18next';

// Define the Webhook interface to represent a webhook object
interface Webhook {
  id?: string;
  name: string;
  url: string;
  isActive: boolean;
}

interface WebhookModalProps {
  isOpen: boolean;
  onClose: () => void;
  workspaceId: string;
}

export const WebhookModal: React.FC<WebhookModalProps> = ({ isOpen, onClose, workspaceId }) => {
  const { t } = useTranslation();
  const [webhooks, setWebhooks] = useState<Webhook[]>([]);
  const [newName, setNewName] = useState('');
  const [newUrl, setNewUrl] = useState('');
  const [loading, setLoading] = useState(false);

  // Fetch webhooks when the modal opens or when the workspaceId changes
  useEffect(() => {
    if (isOpen) {
      fetchWebhooks();
    }
  }, [isOpen, workspaceId]);

  const fetchWebhooks = async () => {
    try {
        const token = localStorage.getItem('livingdocs.auth.token');
        const response = await fetch(`http://localhost:8080/api/v1/workspaces/${workspaceId}/webhooks`, { 
            headers: {
                'Authorization': `Bearer ${token}`
            }
        });
    if (response.ok) {
        const data = await response.json();
        setWebhooks(data);
    }
    } catch (error) {
    console.error('Failed to fetch webhooks:', error);
    }
};

    const handleSave = async () => {
        if (!newName || !newUrl) return;
        setLoading(true);
        try {
            const token = localStorage.getItem('livingdocs.auth.token');
            
            const response = await fetch(`http://localhost:8080/api/v1/workspaces/${workspaceId}/webhooks`, {
                method: 'POST',
                headers: {
                    'Content-Type': 'application/json',
                    'Authorization': `Bearer ${token}`
                },
            body: JSON.stringify({ name: newName, url: newUrl, isActive: true })
        });
      if (response.ok) {
        alert(t("webhook.successMsg"));
        setNewName('');
        setNewUrl('');
        fetchWebhooks(); 
      }
    } catch (error) {
      console.error('Failed to save webhook:', error);
    } finally {
      setLoading(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg p-6 w-full max-w-md shadow-xl">
        <div className="flex justify-between items-center mb-4">
          <h2 className="text-xl font-bold">{t("webhook.title")}</h2>
          <button onClick={onClose} className="text-gray-500 hover:text-gray-700">✕</button>
        </div>

        {/* List of existing webhooks */}
        <div className="mb-6 max-h-40 overflow-y-auto">
          {webhooks.length === 0 ? (
            <p className="text-gray-500 italic text-sm">{t("webhook.emptyState")}</p>
          ) : (
            <ul className="space-y-2">
              {webhooks.map((wh, index) => (
                <li key={index} className="bg-gray-50 p-2 rounded border text-sm flex justify-between items-center">
                  <span className="font-semibold">{wh.name}</span>
                  <span className="text-green-600 text-xs px-2 py-1 bg-green-100 rounded-full">Active</span>
                </li>
              ))}
            </ul>
          )}
        </div>

        {/* Form to add a new webhook */}
        <div className="space-y-4 border-t pt-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">{t("webhook.nameLabel")}</label>
            <input 
              type="text" 
              value={newName} 
              onChange={(e) => setNewName(e.target.value)}
              className="w-full border rounded-md px-3 py-2 text-sm"
              placeholder="Discord Team Chat"
            />
          </div>
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">{t("webhook.urlLabel")}</label>
            <input 
              type="text" 
              value={newUrl} 
              onChange={(e) => setNewUrl(e.target.value)}
              className="w-full border rounded-md px-3 py-2 text-sm"
              placeholder="https://discord.com/api/webhooks/..."
            />
          </div>
          <button 
            onClick={handleSave}
            disabled={loading || !newName || !newUrl}
            className="w-full bg-blue-600 text-white font-semibold py-2 rounded-md hover:bg-blue-700 disabled:opacity-50 transition-colors"
          >
            {loading ? "..." : t("webhook.saveBtn")}
          </button>
        </div>
      </div>
    </div>
  );
};
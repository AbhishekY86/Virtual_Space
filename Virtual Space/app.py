from flask import Flask, send_from_directory, request, jsonify
from flask_cors import CORS
import os
import uuid

app = Flask(__name__)
CORS(app)
UPLOAD_FOLDER = 'Uploads'
os.makedirs(UPLOAD_FOLDER, exist_ok=True)
MAX_FILE_SIZE = 10 * 1024 * 1024  # 10MB limit

@app.route('/')
def serve_index():
    return send_from_directory('.', 'index.html')

@app.route('/<path:path>')
def serve_static(path):
    return send_from_directory('.', path)

@app.route('/upload', methods=['POST'])
def upload_file():
    if 'file' not in request.files:
        return jsonify({'error': 'No file provided'}), 400
    file = request.files['file']
    if file.filename == '':
        return jsonify({'error': 'No file selected'}), 400
    if file.content_length > MAX_FILE_SIZE:
        return jsonify({'error': 'File size exceeds 10MB limit'}), 400
    filename = f"{uuid.uuid4()}_{file.filename}"
    file_path = os.path.join(UPLOAD_FOLDER, filename)
    file.save(file_path)
    file_url = f"/Uploads/{filename}"
    return jsonify({'fileUrl': file_url})

if __name__ == '__main__':
    app.run(debug=True, host='0.0.0.0', port=5001)
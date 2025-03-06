from flask import Flask, request, jsonify, render_template

app = Flask(__name__)

roll_angle = 0.0

@app.route('/')
def index():
    return render_template('index.html')

@app.route('/data', methods=['POST', 'GET'])
def data():
    global roll_angle
    if request.method == 'POST':
        roll_angle = request.json.get("roll", 0.0)
        return jsonify({"status": "OK"})
    return jsonify({"roll": roll_angle})

if __name__ == '__main__':
    app.run(host='0.0.0.0', port=5000, debug=True)

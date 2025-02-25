import http from "k6/http";
import {check, sleep} from "k6";

const SETUP = __ENV.SETUP;
const config = JSON.parse(open('./setup.json'))[SETUP];

export let options = config.options;

export default function () {
    const url = `http://${config.host}:8080/get-message`;

    let multiplier;
    if (Array.isArray(config.multiplier)) {
        multiplier = config.multiplier[ Math.floor(Math.random() * config.multiplier.length) ];
    } else { multiplier = config.multiplier; }
    const payload = JSON.stringify({
        multiplier: multiplier,
        text: config.text
    });
    const params = {
        headers: {
            "Content-Type": "application/json"
        },
    };
    let res = http.post(url, payload, params);
    check(res, {
        "status is 200": (r) => r.status === 200,
    });
    sleep(config.sleep);
}

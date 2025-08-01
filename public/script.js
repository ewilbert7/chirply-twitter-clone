const baseUrl = 'http://localhost:24613'; //the base url of the API, initialised to localhost (can be changed)

//On loading the page
document.addEventListener('DOMContentLoaded', async () => {

    await loadChirps();

    const postForm = document.getElementById('post-form');
    postForm.addEventListener('submit', async (event) => {
        event.preventDefault();
        await postChirp();
    });
});

/**
 * Gets the chirps from the server and
 * displays to the timeline
 */
async function loadChirps() {
    //send the request
    try {
        const response = await fetch(baseUrl + '/chirps');

        if (!response.ok) {
            throw new Error('Failed to fetch chirps');
        }

        const data = await response.json();
        console.log('Fetched chirps:', data);

        const chirps = data.chirps;

        const timeline = document.getElementById('timeline');
        timeline.innerHTML = ''; //Clear the timeline before appending new chirps

        displayChirps(chirps); //display chirps to timeline
    } catch (error) {
        console.error('Error loading chirps:', error);
    }
}

/**
 * Display the chirps to the timeline
 * @param {Array} chirps array of chirps to be posted 
 */
function displayChirps(chirps) {
    const timeline = document.getElementById('timeline');

    chirps.forEach(chirp => {
        const chirpElement = document.createElement('div');
        chirpElement.classList.add('chirp');
        chirpElement.innerHTML = `
        <h3 class="username">${chirp.username}</h3>
        <p class="content">${chirp.content}</p>
        <p class="posted_at">Posted at: ${chirp.posted_at}</p>
        `;
        timeline.appendChild(chirpElement);
    });
}
/**
 * Validates the username and content of a chirp
 * 
 * @param {String} username 
 * @param {String} content 
 * @returns 
 */
function validateChirp(username, content) {
    if (!username || !content) {
        alert('Please enter both a username and content.');
        return;
    }

    if (username.length > 20) {
        alert('Username must be less than 20 characters.');
        return;
    }

    if (content.length > 200) {
        alert('Content must be less than 200 characters.');
        return;
    }
}

/**
 * Post a chirp to the timeline
 */
async function postChirp() {
    const usernameInput = document.getElementById('username');
    const contentInput = document.getElementById('content');

    const username = usernameInput.value.trim();
    const content = contentInput.value.trim();

    //validate inputs
    validateChirp(username, content);

    //send request
    try {
        const response = await fetch(baseUrl + '/chirps', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'application/json',
            },
            body: JSON.stringify({ username, content })
        });

        if (!response.ok) {
            throw new Error('Failed to post chirp');
        }

        const newChirp = await response.json();

        displayChirps([newChirp]); //add new chirp to the timeline

        //Clear the form inputs
        usernameInput.value = '';
        contentInput.value = '';

        alert('Chirp posted successfully!');
    } catch (error) {
        console.error(error);
    }
}